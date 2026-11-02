"""Independent reference model of STOP_LOSS_RULES.md, written separately from the API to cross-check its numbers.

Usage: python3 tools/reference_model.py data  -> prints JSON with per-file counts, alerts, and spend per member/policy year.
"""
import csv, json, re, sys, os
from datetime import date
from decimal import Decimal

data = sys.argv[1]
members = {r['member_id']: r['group_id'] for r in csv.DictReader(open(f'{data}/members.csv', encoding='utf-8-sig'))}
pys = []  # (group, start, end, deductible_cents)
for r in csv.DictReader(open(f'{data}/policies.csv', encoding='utf-8-sig')):
    pys.append((r['group_id'], date.fromisoformat(r['policy_start']), date.fromisoformat(r['policy_end']),
                int(Decimal(r['specific_deductible']) * 100)))
lasers = {(r['member_id'], date.fromisoformat(r['policy_start'])): int(Decimal(r['specific_deductible']) * 100)
          for r in csv.DictReader(open(f'{data}/lasers.csv', encoding='utf-8-sig'))}

LAYOUTS = {
    ('claim id', 'adj seq', 'member id', 'service date', 'paid date', 'paid amount', 'status', 'diagnosis code'):
        dict(claim='claim id', ver='adj seq', mem='member id', sd='service date', pd='paid date', amt='paid amount', st='status', dx='diagnosis code'),
    ('claim_number', 'version', 'member', 'dos', 'paid_dt', 'amount', 'claim_status'):
        dict(claim='claim_number', ver='version', mem='member', sd='dos', pd='paid_dt', amt='amount', st='claim_status', dx=None),
}
PAID = {'PAID', 'P'}; REV = {'REVERSED', 'R', 'VOID', 'VOIDED', 'V'}

def pdate(s):
    s = s.strip()
    m = re.fullmatch(r'(\d{4})-(\d{2})-(\d{2})', s) or None
    if m: return date(int(m[1]), int(m[2]), int(m[3]))
    m = re.fullmatch(r'(\d{1,2})/(\d{1,2})/(\d{4})', s)
    if m: return date(int(m[3]), int(m[1]), int(m[2]))
    raise ValueError(s)

def pamt(s):
    t = s.replace('$', '').replace(',', '').strip()
    if not re.fullmatch(r'\d+(\.\d{1,2})?', t): raise ValueError(s)
    return int(Decimal(t) * 100)

def py_for(group, sd):
    for g, s, e, d in pys:
        if g == group and s <= sd <= e: return (g, s)
    return None

def deductible(member, py):
    g, s = py
    if (member, s) in lasers: return lasers[(member, s)]
    return next(d for gg, ss, e, d in pys if gg == g and ss == s)

versions = {}   # (group, claim) -> {ver: values}
alerts = set()  # (member, py, t)
alert_log = []
out = {'files': {}, 'alerts': alert_log}

def spend_of(member, py):
    tot = 0
    for (g, c), vs in versions.items():
        cur = vs[max(vs)]
        if cur['mem'] == member and py_for(g, cur['sd']) == py:
            tot += 0 if cur['st'] == 'reversed' else cur['amt']
    return tot

for fn in sorted(os.listdir(f'{data}/claims')):
    group = fn.split('_')[0]
    lines = open(f'{data}/claims/{fn}', encoding='utf-8-sig', newline='').read().splitlines()
    counts = dict(rows=0, applied=0, unchanged=0, olderIgnored=0, summary=0, errors=0)
    errors = []
    hdr_i = next(i for i, l in enumerate(lines) if l.strip() and not l.startswith('#'))
    hdr = tuple(h.strip().lower() for h in next(csv.reader([lines[hdr_i]])))
    lay = LAYOUTS[hdr]
    touched = set()
    for i in range(hdr_i + 1, len(lines)):
        if not lines[i].strip(): continue
        row = next(csv.reader([lines[i]]))
        counts['rows'] += 1
        d = dict(zip(hdr, row))
        if d[lay['claim']].strip().upper() in ('TRAILER', 'TOTAL', 'SUMMARY'):
            counts['summary'] += 1; continue
        try:
            mem = d[lay['mem']].strip()
            if mem not in members: raise ValueError(f'unknown member {mem}')
            if members[mem] != group: raise ValueError(f'member {mem} belongs to {members[mem]}')
            ver = int(d[lay['ver']].strip())
            sd = pdate(d[lay['sd']]); amt = pamt(d[lay['amt']])
            st = d[lay['st']].strip().upper()
            st = 'paid' if st in PAID else 'reversed' if st in REV else (_ for _ in ()).throw(ValueError('status'))
            vals = dict(mem=mem, sd=sd, pd=d[lay['pd']].strip(), amt=amt, st=st, dx=d.get(lay['dx'] or '', '').strip())
        except Exception as e:
            counts['errors'] += 1; errors.append((i + 1, str(e))); continue
        key = (group, d[lay['claim']].strip())
        vs = versions.setdefault(key, {})
        if ver in vs:
            if vs[ver] == vals: counts['unchanged'] += 1
            else: counts['errors'] += 1; errors.append((i + 1, 'conflict'))
            continue
        if vs and ver < max(vs):
            vs[ver] = vals; counts['olderIgnored'] += 1; continue
        if vs:
            old = vs[max(vs)]; opy = py_for(group, old['sd'])
            if opy: touched.add((old['mem'], opy))
        vs[ver] = vals; counts['applied'] += 1
        npy = py_for(group, sd)
        if npy: touched.add((mem, npy))
    for (m, py) in sorted(touched, key=str):
        sp, dd = spend_of(m, py), deductible(m, py)
        for t in (50, 75, 100):
            if sp * 100 >= t * dd and (m, py, t) not in alerts:
                alerts.add((m, py, t)); alert_log.append(dict(file=fn, member=m, py=str(py[1]), t=t, spend=sp / 100))
    out['files'][fn] = dict(counts=counts, errors=errors)

spend = {}
for (g, c), vs in versions.items():
    cur = vs[max(vs)]; py = py_for(g, cur['sd'])
    if py and cur['st'] == 'paid':
        k = f"{cur['mem']}|{py[1]}"; spend[k] = spend.get(k, 0) + cur['amt']
out['spend_cents'] = spend
print(json.dumps(out, default=str, indent=1))
