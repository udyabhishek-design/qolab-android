/// A single stored cookie.
class CookieEntry {
  CookieEntry({
    required this.name,
    required this.value,
    this.expiresAt,
    this.isSession = true,
  });

  final String name;
  String value;
  final DateTime? expiresAt;
  final bool isSession;

  bool get isExpired {
    final exp = expiresAt;
    if (exp == null) return false;
    return DateTime.now().isAfter(exp);
  }

  Map<String, dynamic> toJson() => {
        'name': name,
        'value': value,
        'expiresAt': expiresAt?.toIso8601String(),
        'session': isSession,
      };

  static CookieEntry fromJson(Map<String, dynamic> json) => CookieEntry(
        name: json['name'] as String,
        value: json['value'] as String,
        expiresAt: json['expiresAt'] == null
            ? null
            : DateTime.parse(json['expiresAt'] as String),
        isSession: (json['session'] as bool? ?? true),
      );
}

/// A managed cookie jar for one origin.
///
/// - Captures Set-Cookie headers (multiple per response).
/// - Replaces existing cookies by name (Laravel rotates the session cookie
///   on login; the rotated value must win).
/// - Honours Max-Age/expires and deletes cookies set with a past date or
///   empty value.
/// - Emits a Cookie request header for stored, unexpired cookies.
/// - Persists to/from JSON so the jar can live in secure storage.
class CookieJar {
  final Map<String, CookieEntry> _cookies = {};

  int get length => _cookies.length;

  /// Parse a Set-Cookie header list and merge into the jar.
  void storeFrom(List<String> setCookieHeaders) {
    for (final raw in setCookieHeaders) {
      final entry = _parseSetCookie(raw);
      if (entry == null) continue;
      if (entry.value.isEmpty || entry.isExpired) {
        _cookies.remove(entry.name);
        continue;
      }
      _cookies[entry.name] = entry;
    }
    _pruneExpired();
  }

  /// Cookie header value for the next request, or null when empty.
  String? cookieHeader() {
    _pruneExpired();
    if (_cookies.isEmpty) return null;
    return _cookies.values.map((c) => '${c.name}=${c.value}').join('; ');
  }

  CookieEntry? operator [](String name) => _cookies[name];

  void clear() => _cookies.clear();

  /// Copy all unexpired entries from [other], replacing this jar's contents.
  void adoptFrom(CookieJar other) {
    _cookies
      ..clear()
      ..addAll(other._cookies);
    _pruneExpired();
  }

  /// True if the jar holds a non-empty Laravel session cookie.
  bool get hasSessionCookie {
    final session = _cookies[kSessionCookieName];
    return session != null && session.value.isNotEmpty && !session.isExpired;
  }

  /// Laravel's session cookie name (verified against Development).
  static const String kSessionCookieName = 'qolab-session';

  Map<String, dynamic> toPersistJson() => {
        'cookies': _cookies.values.map((c) => c.toJson()).toList(),
      };

  static CookieJar fromPersistJson(Map<String, dynamic> json) {
    final jar = CookieJar();
    for (final raw in (json['cookies'] as List? ?? [])) {
      final entry = CookieEntry.fromJson(Map<String, dynamic>.from(raw as Map));
      if (!entry.isExpired && entry.value.isNotEmpty) {
        jar._cookies[entry.name] = entry;
      }
    }
    return jar;
  }

  void _pruneExpired() {
    _cookies.removeWhere((_, c) => c.isExpired);
  }

  static CookieEntry? _parseSetCookie(String raw) {
    // Split on the FIRST ';' — attributes never contain ';'.
    final parts = raw.split(';');
    if (parts.isEmpty) return null;
    final nv = parts.first.split('=');
    if (nv.length < 2) return null;
    final name = nv[0].trim();
    // Value may itself contain '=' (Laravel encrypted cookies do not, but
    // be safe: rejoin everything after the first '=').
    final value = nv.sublist(1).join('=').trim();

    DateTime? expiresAt;
    var isSession = true;
    for (var i = 1; i < parts.length; i++) {
      final attr = parts[i].trim().toLowerCase();
      if (attr.startsWith('max-age=')) {
        final secs = int.tryParse(attr.substring(8));
        if (secs != null) {
          expiresAt = DateTime.now().add(Duration(seconds: secs));
          isSession = false;
        }
      } else if (attr.startsWith('expires=')) {
        final d = HttpDateParser.tryParse(parts[i].trim().substring(8));
        if (d != null) {
          expiresAt = d;
          isSession = false;
        }
      }
    }
    return CookieEntry(
        name: name, value: value, expiresAt: expiresAt, isSession: isSession);
  }
}

/// Minimal RFC 7231 date parser for Set-Cookie expires attributes.
class HttpDateParser {
  static DateTime? tryParse(String s) {
    try {
      final d = HttpDate.parse(s);
      return d;
    } catch (_) {
      return null;
    }
  }
}

/// Web-standard HttpDate lives in dart:io; keep a thin alias for testability.
class HttpDate {
  static DateTime parse(String s) {
    final parsed = DateTime.tryParse(_normalize(s));
    if (parsed != null) return parsed;
    // Fall back to the platform parser via dart:io format is not available
    // in pure Dart; browsers/Go use fixed formats. Parse common shapes:
    final m = RegExp(
            r'^\w{3},\s(\d{2})\s(\w{3})\s(\d{4})\s(\d{2}):(\d{2}):(\d{2})\sGMT$')
        .firstMatch(s.trim());
    if (m != null) {
      const months = [
        'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
        'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'
      ];
      final mon = months.indexOf(m.group(2)!) + 1;
      if (mon > 0) {
        return DateTime.utc(
          int.parse(m.group(3)!),
          mon,
          int.parse(m.group(1)!),
          int.parse(m.group(4)!),
          int.parse(m.group(5)!),
          int.parse(m.group(6)!),
        );
      }
    }
    throw const FormatException('unparseable HTTP date');
  }

  static String _normalize(String s) => s;
}
