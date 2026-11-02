"""End-to-end check against a running stack (`docker compose up`), stdlib only.

1. Signs in through the Firebase Auth emulator as the seeded analyst and brokers.
2. Uploads every sample claim file through the real HTTP API, in file-name order (twice for one file, to prove
   re-uploads change nothing).
3. Compares per-file counts, failed rows, alerts and member spend with tools/reference_model.py, which implements
   STOP_LOSS_RULES.md independently of the API.
4. Checks broker isolation over HTTP (broker A never sees Group B).

Run on a fresh database: `docker compose down -v && docker compose up -d --build && python3 tools/e2e_check.py`
"""
import json, os, subprocess, sys, urllib.error, urllib.request, uuid

API = os.environ.get('API_URL', 'http://localhost:8080')
AUTH = os.environ.get('AUTH_EMULATOR_URL', 'http://localhost:9099')
PASSWORD = os.environ.get('SEED_PASSWORD', 'Password123!')
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, 'data')
failures = []


def check(cond, msg):
    print(('  ok   ' if cond else '  FAIL ') + msg)
    if not cond:
        failures.append(msg)


def req(method, url, token=None, body=None, headers=None):
    h = dict(headers or {})
    if token:
        h['Authorization'] = f'Bearer {token}'
    if isinstance(body, (dict, list)):
        body = json.dumps(body).encode()
        h['Content-Type'] = 'application/json'
    r = urllib.request.Request(url, data=body, method=method, headers=h)
    try:
        with urllib.request.urlopen(r) as resp:
            return resp.status, json.loads(resp.read() or b'null')
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, raw.decode(errors='replace')


def sign_in(email):
    url = f'{AUTH}/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=demo-api-key'
    status, body = req('POST', url, body={'email': email, 'password': PASSWORD, 'returnSecureToken': True})
    assert status == 200, f'sign-in failed for {email}: {status} {body}'
    return body['idToken']


def upload(token, path):
    boundary = uuid.uuid4().hex
    name = os.path.basename(path)
    content = open(path, 'rb').read()
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
            f'Content-Type: text/csv\r\n\r\n').encode() + content + f'\r\n--{boundary}--\r\n'.encode()
    return req('POST', f'{API}/api/uploads', token, body, {'Content-Type': f'multipart/form-data; boundary={boundary}'})


def main():
    ref = json.loads(subprocess.check_output([sys.executable, os.path.join(ROOT, 'tools', 'reference_model.py'), DATA]))
    analyst, broker_a, broker_b = (sign_in(e) for e in
                                   ('analyst@example.test', 'broker.a@example.test', 'broker.b@example.test'))

    print('uploads vs reference model')
    files = sorted(os.listdir(os.path.join(DATA, 'claims')))
    for name in files:
        status, res = upload(analyst, os.path.join(DATA, 'claims', name))
        exp = ref['files'][name]
        got = {k: res['counts'].get(k) for k in exp['counts']} if isinstance(res, dict) and 'counts' in res else res
        check(status in (200, 201) and got == exp['counts'], f'{name}: counts {got} == {exp["counts"]}')
        got_rows = sorted(e['rowNumber'] for e in res.get('errors', []))
        check(got_rows == sorted(r for r, _ in exp['errors']), f'{name}: failed rows {got_rows}')

    print('re-upload changes nothing')
    before = req('GET', f'{API}/api/groups/GA/spend', analyst)[1]
    status, res = upload(analyst, os.path.join(DATA, 'claims', 'GA_2026-09.csv'))
    check(res['counts']['applied'] == 0 and not res['alertsRaised'], 'GA_2026-09 again: 0 applied, 0 alerts')
    check(req('GET', f'{API}/api/groups/GA/spend', analyst)[1] == before, 'GA spend identical after re-upload')

    print('alerts vs reference model')
    _, alerts = req('GET', f'{API}/api/alerts?status=all', analyst)
    got = sorted((a['memberId'], a['policyYearStart'], a['threshold']) for a in alerts)
    exp = sorted((a['member'], a['py'], a['t']) for a in ref['alerts'])
    check(got == exp, f'{len(got)} alerts match reference ({len(exp)} expected)')

    print('spend vs reference model')
    _, groups = req('GET', f'{API}/api/groups', analyst)
    mismatches = 0
    for g in groups:
        for py in g['policyYears']:
            _, sp = req('GET', f'{API}/api/groups/{g["id"]}/spend?policyYearId={py["id"]}', analyst)
            for m in sp['members']:
                exp_cents = ref['spend_cents'].get(f'{m["memberId"]}|{py["startDate"]}', 0)
                if round(m['spend'] * 100) != exp_cents:
                    mismatches += 1
                    print(f'     {m["memberId"]} {py["startDate"]}: api {m["spend"]} ref {exp_cents / 100}')
    check(mismatches == 0, 'every member spend matches reference')

    print('broker isolation over HTTP')
    _, ga = req('GET', f'{API}/api/groups', broker_a)
    check([g['id'] for g in ga] == ['GA'], f'broker.a sees only GA: {[g["id"] for g in ga]}')
    check(req('GET', f'{API}/api/groups/GB/spend', broker_a)[0] == 404, 'broker.a GET /groups/GB/spend -> 404')
    check(req('GET', f'{API}/api/members/B-0002', broker_a)[0] == 404, 'broker.a GET /members/B-0002 -> 404')
    _, a_alerts = req('GET', f'{API}/api/alerts?status=all', broker_a)
    check(a_alerts and all(a['groupId'] == 'GA' for a in a_alerts), 'broker.a alerts are all GA')
    gb_alert = next(a for a in alerts if a['groupId'] == 'GB')
    check(req('POST', f'{API}/api/alerts/{gb_alert["id"]}/acknowledge', broker_a, {'note': 'x'})[0] in (403, 404),
          'broker.a cannot acknowledge a GB alert')
    check(upload(broker_b, os.path.join(DATA, 'claims', 'GB_2026-04.csv'))[0] == 403, 'broker.b cannot upload')
    check(req('GET', f'{API}/api/groups', None)[0] == 401, 'no token -> 401')

    print('acknowledge flow')
    open_alert = next(a for a in alerts if a['status'] == 'open')
    check(req('POST', f'{API}/api/alerts/{open_alert["id"]}/acknowledge', analyst, {'note': ''})[0] == 400,
          'empty note -> 400')
    status, acked = req('POST', f'{API}/api/alerts/{open_alert["id"]}/acknowledge', analyst,
                        {'note': 'case manager assigned'})
    check(status == 200 and acked['status'] == 'acknowledged', 'analyst acknowledges with note')
    check(req('POST', f'{API}/api/alerts/{open_alert["id"]}/acknowledge', analyst, {'note': 'again'})[0] == 409,
          'second acknowledge -> 409')

    print(f'\n{"PASS" if not failures else f"FAIL ({len(failures)})"}')
    sys.exit(1 if failures else 0)


if __name__ == '__main__':
    main()
