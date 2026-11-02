import 'dart:convert';
import 'dart:typed_data';

import 'package:http/http.dart' as http;

import '../models.dart';
import 'api_client.dart';

/// [ApiClient] over HTTP. Every call carries `Authorization: Bearer <token>`.
class HttpApiClient implements ApiClient {
  HttpApiClient({
    required this.tokenProvider,
    required this.onUnauthorized,
    String baseUrl = '',
    http.Client? client,
  }) : _base = _resolveBase(baseUrl),
       _http = client ?? http.Client();

  /// Returns the current user's Firebase ID token (null when signed out).
  final Future<String?> Function() tokenProvider;

  /// Called on any 401 so the app can sign the user out.
  final void Function() onUnauthorized;

  final Uri _base;
  final http.Client _http;

  /// Empty → the page's own origin (`Uri.base` on web), so `/api` goes through
  /// the same nginx / Firebase Hosting that served the app.
  static Uri _resolveBase(String baseUrl) {
    if (baseUrl.isEmpty) return Uri.base.resolve('/');
    return Uri.parse(baseUrl.endsWith('/') ? baseUrl : '$baseUrl/');
  }

  Uri _uri(List<String> segments, [Map<String, String>? query]) {
    final path = ['api', ...segments].map(Uri.encodeComponent).join('/');
    final uri = _base.resolve(path);
    return query == null || query.isEmpty
        ? uri
        : uri.replace(queryParameters: query);
  }

  @override
  Future<Profile> me() => _getObject(_uri(['me']), Profile.fromJson);

  @override
  Future<List<Group>> groups() => _getList(_uri(['groups']), Group.fromJson);

  @override
  Future<GroupSpend> groupSpend(String groupId, {String? policyYearId}) =>
      _getObject(
        _uri(['groups', groupId, 'spend'], {'policyYearId': ?policyYearId}),
        GroupSpend.fromJson,
      );

  @override
  Future<MemberDetail> member(String memberId) =>
      _getObject(_uri(['members', memberId]), MemberDetail.fromJson);

  @override
  Future<List<Alert>> alerts({
    AlertFilter status = AlertFilter.open,
    String? groupId,
  }) => _getList(
    _uri(['alerts'], {'status': status.name, 'groupId': ?groupId}),
    Alert.fromJson,
  );

  @override
  Future<Alert> acknowledgeAlert(String alertId, String note) async {
    final request =
        http.Request('POST', _uri(['alerts', alertId, 'acknowledge']))
          ..headers['Content-Type'] = 'application/json'
          ..body = jsonEncode({'note': note});
    final response = await _send(request);
    return _parse(() => Alert.fromJson(_decode(response) as Json));
  }

  @override
  Future<UploadResult> uploadFile(String filename, Uint8List bytes) async {
    final request = http.MultipartRequest('POST', _uri(['uploads']))
      ..files.add(
        http.MultipartFile.fromBytes('file', bytes, filename: filename),
      );
    // A rejected file comes back as 400 with an UploadResult body; that is a
    // result to show, not an error.
    final response = await _send(
      request,
      acceptBody: (status, body) =>
          status == 400 && body is Json && body['status'] == 'rejected',
    );
    return _parse(() => UploadResult.fromJson(_decode(response) as Json));
  }

  @override
  Future<List<UploadResult>> uploads() =>
      _getList(_uri(['uploads']), UploadResult.fromJson);

  @override
  Future<UploadResult> upload(String uploadId) =>
      _getObject(_uri(['uploads', uploadId]), UploadResult.fromJson);

  // ---- plumbing ------------------------------------------------------------

  Future<T> _getObject<T>(Uri uri, T Function(Json) fromJson) async {
    final response = await _send(http.Request('GET', uri));
    return _parse(() => fromJson(_decode(response) as Json));
  }

  Future<List<T>> _getList<T>(Uri uri, T Function(Json) fromJson) async {
    final response = await _send(http.Request('GET', uri));
    return _parse(
      () => [
        for (final item in _decode(response) as List) fromJson(item as Json),
      ],
    );
  }

  /// Sends [request] with the bearer token and maps non-2xx statuses to
  /// [ApiException]s, unless [acceptBody] says the error body is a result.
  Future<http.Response> _send(
    http.BaseRequest request, {
    bool Function(int status, Object? body)? acceptBody,
  }) async {
    final token = await tokenProvider();
    if (token == null) {
      onUnauthorized();
      throw const UnauthorizedException('You are signed out.');
    }
    request.headers['Authorization'] = 'Bearer $token';
    request.headers['Accept'] = 'application/json';

    final http.Response response;
    try {
      response = await http.Response.fromStream(await _http.send(request));
    } on http.ClientException catch (e) {
      throw NetworkException('Could not reach the API (${e.message}).');
    }

    final status = response.statusCode;
    if (status >= 200 && status < 300) return response;

    Object? body;
    try {
      body = _decode(response);
    } on FormatException {
      body = null;
    }
    if (acceptBody != null && acceptBody(status, body)) return response;

    final message = body is Json && body['error'] is String
        ? body['error'] as String
        : 'Request failed (HTTP $status).';
    switch (status) {
      case 401:
        onUnauthorized();
        throw UnauthorizedException(message);
      case 403:
        throw ForbiddenException(message);
      case 404:
        throw NotFoundException(message);
      case 409:
        throw ConflictException(message);
      case 400:
        throw BadRequestException(message);
      default:
        throw ServerException(message, statusCode: status);
    }
  }

  static Object? _decode(http.Response response) =>
      jsonDecode(utf8.decode(response.bodyBytes));

  /// Turns a shape mismatch (TypeError from a cast, FormatException) into an
  /// [ApiException] the UI already knows how to show.
  static T _parse<T>(T Function() parse) {
    try {
      return parse();
    } on ApiException {
      rethrow;
    } catch (e) {
      throw ResponseFormatException('Unexpected response from the API: $e');
    }
  }
}
