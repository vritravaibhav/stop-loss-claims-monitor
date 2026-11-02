import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:stoploss_monitor/models.dart';
import 'package:stoploss_monitor/services/api_client.dart';
import 'package:stoploss_monitor/services/http_api_client.dart';

import 'fixtures.dart';

void main() {
  late List<http.Request> requests;
  late int unauthorizedCalls;

  HttpApiClient client(
    http.Response Function(http.Request) handler, {
    String? token = 'token-123',
    String baseUrl = 'http://api.test',
  }) {
    requests = [];
    unauthorizedCalls = 0;
    return HttpApiClient(
      baseUrl: baseUrl,
      tokenProvider: () async => token,
      onUnauthorized: () => unauthorizedCalls++,
      client: MockClient((request) async {
        requests.add(request);
        return handler(request);
      }),
    );
  }

  http.Response json(Object body, [int status = 200]) => http.Response(
    jsonEncode(body),
    status,
    headers: {'content-type': 'application/json'},
  );

  test('sends the bearer token and parses /api/me', () async {
    final api = client((_) => json(fixture('me_analyst')));
    final me = await api.me();
    expect(me.role, Role.analyst);
    expect(requests.single.url.toString(), 'http://api.test/api/me');
    expect(requests.single.headers['Authorization'], 'Bearer token-123');
  });

  test('builds paths and query strings', () async {
    final api = client(
      (r) => r.url.path.endsWith('/spend')
          ? json(fixture('group_spend'))
          : json(fixture('alerts')),
      baseUrl: 'https://example.run.app/',
    );
    await api.groupSpend('GA', policyYearId: '3');
    await api.groupSpend('GA');
    await api.alerts(status: AlertFilter.all, groupId: 'GB');
    await api.alerts();
    expect(requests.map((r) => r.url.toString()), [
      'https://example.run.app/api/groups/GA/spend?policyYearId=3',
      'https://example.run.app/api/groups/GA/spend',
      'https://example.run.app/api/alerts?status=all&groupId=GB',
      'https://example.run.app/api/alerts?status=open',
    ]);
  });

  test('ids are path-encoded', () async {
    final api = client((_) => json(fixture('member')));
    await api.member('A 77/x');
    expect(requests.single.url.path, '/api/members/A%2077%2Fx');
  });

  test('acknowledge posts the note as JSON', () async {
    final api = client((_) => json((fixture('alerts') as List).last as Json));
    final alert = await api.acknowledgeAlert('12', 'Case manager assigned');
    expect(alert.status, AlertStatus.acknowledged);
    final request = requests.single;
    expect(request.method, 'POST');
    expect(request.url.path, '/api/alerts/12/acknowledge');
    expect(jsonDecode(request.body), {'note': 'Case manager assigned'});
  });

  test('401 signs out and throws UnauthorizedException', () async {
    final api = client((_) => json({'error': 'Token expired'}, 401));
    await expectLater(
      api.groups(),
      throwsA(
        isA<UnauthorizedException>().having(
          (e) => e.message,
          'message',
          'Token expired',
        ),
      ),
    );
    expect(unauthorizedCalls, 1);
  });

  test('no token: signs out without calling the API', () async {
    final api = client((_) => json([]), token: null);
    await expectLater(api.groups(), throwsA(isA<UnauthorizedException>()));
    expect(requests, isEmpty);
    expect(unauthorizedCalls, 1);
  });

  test('error statuses map to typed exceptions with the API message', () async {
    Future<ApiException> errorFor(int status) async {
      final api = client((_) => json({'error': 'msg $status'}, status));
      try {
        await api.acknowledgeAlert('1', 'n');
      } on ApiException catch (e) {
        return e;
      }
      fail('expected an ApiException for $status');
    }

    expect(await errorFor(400), isA<BadRequestException>());
    expect(await errorFor(403), isA<ForbiddenException>());
    expect(await errorFor(404), isA<NotFoundException>());
    expect(await errorFor(409), isA<ConflictException>());
    final server = await errorFor(500);
    expect(server, isA<ServerException>());
    expect(server.message, 'msg 500');
    expect(server.statusCode, 500);
    expect(unauthorizedCalls, 0);
  });

  test('non-JSON error body still gives a readable message', () async {
    final api = client((_) => http.Response('<html>Bad gateway</html>', 502));
    await expectLater(
      api.me(),
      throwsA(
        isA<ServerException>().having(
          (e) => e.message,
          'message',
          'Request failed (HTTP 502).',
        ),
      ),
    );
  });

  test(
    'a response with the wrong shape becomes ResponseFormatException',
    () async {
      final api = client((_) => json({'email': 'x'}));
      await expectLater(api.me(), throwsA(isA<ResponseFormatException>()));
    },
  );

  test('network failure becomes NetworkException', () async {
    final api = HttpApiClient(
      baseUrl: 'http://api.test',
      tokenProvider: () async => 't',
      onUnauthorized: () {},
      client: MockClient((_) async => throw http.ClientException('refused')),
    );
    await expectLater(api.me(), throwsA(isA<NetworkException>()));
  });

  group('uploadFile', () {
    final bytes = Uint8List.fromList(utf8.encode('Claim ID,Adj Seq\r\n'));

    test('multipart field "file" with the original filename', () async {
      final api = client((_) => json(fixture('upload_result'), 201));
      final result = await api.uploadFile('GA_2026-04_resend.csv', bytes);
      expect(result.counts.errors, 2);
      expect(result.alertsRaised, hasLength(2));

      final request = requests.single;
      expect(request.method, 'POST');
      expect(request.url.path, '/api/uploads');
      expect(
        request.headers['content-type'],
        startsWith('multipart/form-data'),
      );
      expect(
        request.body,
        contains('name="file"; filename="GA_2026-04_resend.csv"'),
      );
      expect(request.body, contains('Claim ID,Adj Seq\r\n'));
    });

    test('400 with a rejected UploadResult is returned, not thrown', () async {
      final api = client((_) => json(fixture('upload_rejected'), 400));
      final result = await api.uploadFile('GZ_2026-01.csv', bytes);
      expect(result.isRejected, isTrue);
      expect(result.rejectionReason, 'Unknown group GZ');
    });

    test('400 with a plain error body is still an error', () async {
      final api = client((_) => json({'error': 'No file field'}, 400));
      await expectLater(
        api.uploadFile('x.csv', bytes),
        throwsA(
          isA<BadRequestException>().having(
            (e) => e.message,
            'message',
            'No file field',
          ),
        ),
      );
    });

    test('403 for a broker', () async {
      final api = client((_) => json({'error': 'Analysts only'}, 403));
      await expectLater(
        api.uploadFile('x.csv', bytes),
        throwsA(isA<ForbiddenException>()),
      );
    });
  });

  test('upload history and detail', () async {
    final api = client(
      (r) => r.url.path == '/api/uploads'
          ? json(fixture('uploads'))
          : json(fixture('upload_result')),
    );
    final list = await api.uploads();
    expect(list, hasLength(2));
    final detail = await api.upload(list.last.id);
    expect(detail.errors, hasLength(2));
    expect(requests.last.url.path, '/api/uploads/5');
  });
}
