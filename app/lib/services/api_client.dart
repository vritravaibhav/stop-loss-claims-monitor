import 'dart:typed_data';

import '../models.dart';

/// Which alerts `GET /api/alerts` returns.
enum AlertFilter { open, acknowledged, all }

/// The HTTP API from docs/SPEC.md. Implemented by `HttpApiClient`; widget
/// tests use a fake.
abstract interface class ApiClient {
  Future<Profile> me();

  Future<List<Group>> groups();

  /// [policyYearId] null → the API's default (the group's current year).
  Future<GroupSpend> groupSpend(String groupId, {String? policyYearId});

  Future<MemberDetail> member(String memberId);

  /// Newest first.
  Future<List<Alert>> alerts({
    AlertFilter status = AlertFilter.open,
    String? groupId,
  });

  /// Analyst only. Returns the updated alert.
  Future<Alert> acknowledgeAlert(String alertId, String note);

  /// Analyst only. A rejected file is a normal result (status "rejected"),
  /// not an exception.
  Future<UploadResult> uploadFile(String filename, Uint8List bytes);

  /// Analyst only. Newest first, without row errors.
  Future<List<UploadResult>> uploads();

  /// Analyst only. Full result including row errors.
  Future<UploadResult> upload(String uploadId);
}

/// Base class of every error [ApiClient] throws. [message] is the API's
/// `{error}` text when there is one, and is safe to show the user.
sealed class ApiException implements Exception {
  const ApiException(this.message, {this.statusCode});

  final String message;
  final int? statusCode;

  @override
  String toString() => message;
}

/// 401: missing/expired token. The client also signs the user out.
class UnauthorizedException extends ApiException {
  const UnauthorizedException(super.message) : super(statusCode: 401);
}

/// 403: signed in but not allowed (unknown user, broker on a write).
class ForbiddenException extends ApiException {
  const ForbiddenException(super.message) : super(statusCode: 403);
}

/// 404: does not exist, or is not visible to this user.
class NotFoundException extends ApiException {
  const NotFoundException(super.message) : super(statusCode: 404);
}

/// 409: e.g. acknowledging an alert that is no longer open.
class ConflictException extends ApiException {
  const ConflictException(super.message) : super(statusCode: 409);
}

/// 400: invalid request (e.g. empty note).
class BadRequestException extends ApiException {
  const BadRequestException(super.message) : super(statusCode: 400);
}

/// 5xx or any other unexpected status.
class ServerException extends ApiException {
  const ServerException(super.message, {required int super.statusCode});
}

/// The request never got an HTTP response (offline, CORS, DNS...).
class NetworkException extends ApiException {
  const NetworkException(super.message);
}

/// The API answered, but not with the shape in SPEC.
class ResponseFormatException extends ApiException {
  const ResponseFormatException(super.message);
}
