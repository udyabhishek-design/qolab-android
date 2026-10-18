import 'dart:convert';

/// Error taxonomy for the Qolab API client.
///
/// Users must never see raw exceptions — every failure maps to one of these
/// categories with a human message, and callers decide retry behavior.
enum ApiErrorKind {
  /// No connectivity / socket failure.
  network,

  /// Request timed out.
  timeout,

  /// 419 — CSRF token expired or session invalid. Recoverable by
  /// re-bootstrapping the session when appropriate.
  csrfExpired,

  /// 422 — Laravel validation errors, keyed by field.
  validation,

  /// 429 — rate limited (login throttle is 5/min per email+IP).
  rateLimited,

  /// 500 / 502 / 503 / 504 — server error or maintenance.
  server,

  /// Authenticated request redirected to /login (or 401) — session expired
  /// or invalid. Stored credentials must be cleared.
  unauthenticated,

  /// 409 with X-Inertia-Location: Inertia asset-version mismatch.
  inertiaVersionMismatch,

  /// 409 without X-Inertia-Location: unrelated conflict.
  conflict,

  /// Anything unexpected.
  unknown,
}

class ApiException implements Exception {
  const ApiException(this.kind, this.message, {this.fieldErrors});

  final ApiErrorKind kind;
  final String message;

  /// 422 responses: field name → first message.
  final Map<String, String>? fieldErrors;

  @override
  String toString() => 'ApiException($kind): $message';
}

/// Map an HTTP status + context to an [ApiException].
ApiException apiErrorFromStatus(int status,
    {String? body,
    bool redirectedToLogin = false,
    String? inertiaLocation}) {
  if (redirectedToLogin || status == 401) {
    return const ApiException(ApiErrorKind.unauthenticated,
        'Your session has expired. Please sign in again.');
  }
  switch (status) {
    case 409:
      // Inertia's version-mismatch protocol answers 409 with an
      // X-Inertia-Location header (server asks for a full reload).
      // Without that header it is an unrelated conflict — still surfaced
      // explicitly, never silently generic.
      if (inertiaLocation != null) {
        return const ApiException(
            ApiErrorKind.inertiaVersionMismatch,
            'Qolab could not load your account (app out of sync with the '
            'server). Please try signing in again.');
      }
      return const ApiException(ApiErrorKind.conflict,
          'The request conflicted with the server. Please try again.');
    case 419:
      return const ApiException(ApiErrorKind.csrfExpired,
          'Your session expired. Please try signing in again.');
    case 422:
      final fields = parseFieldErrors(body);
      final first = fields.values.isEmpty
          ? 'Please check the entered details.'
          : fields.values.first;
      return ApiException(ApiErrorKind.validation, first, fieldErrors: fields);
    case 429:
      return const ApiException(ApiErrorKind.rateLimited,
          'Too many attempts. Please wait a minute and try again.');
    case 500:
    case 502:
    case 503:
    case 504:
      return const ApiException(
          ApiErrorKind.server, 'Qolab is temporarily unavailable.');
    default:
      return const ApiException(
          ApiErrorKind.unknown, 'Something went wrong. Please try again.');
  }
}

/// Laravel 422 body: {"message": "...", "errors": {"email": ["..."]}}
Map<String, String> parseFieldErrors(String? body) {
  if (body == null) return {};
  try {
    final decoded = jsonDecode(body);
    if (decoded is Map) {
      final errors = decoded['errors'];
      if (errors is Map) {
        final out = <String, String>{};
        errors.forEach((k, v) {
          if (v is List && v.isNotEmpty) {
            out[k.toString()] = v.first.toString();
          }
        });
        return out;
      }
      final message = decoded['message'];
      if (message is String && message.isNotEmpty) {
        return {'general': message};
      }
    }
  } catch (_) {
    // Non-JSON body — nothing to extract.
  }
  return {};
}

/// Network-level failure classification (socket errors, timeouts).
ApiException apiErrorFromNetwork(Object error) {
  final text = error.toString().toLowerCase();
  if (text.contains('timeout') || text.contains('timed out')) {
    return const ApiException(ApiErrorKind.timeout,
        'The connection timed out. Please try again.');
  }
  return const ApiException(ApiErrorKind.network,
      'No connection to Qolab. Check your internet and try again.');
}
