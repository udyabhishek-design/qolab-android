import 'dart:convert';

/// Authenticated user identity extracted from an Inertia page's auth.user.
class QolabUser {
  const QolabUser({
    required this.id,
    required this.name,
    this.email,
    this.jobTitle,
    this.departmentName,
    this.avatarPath,
  });

  final int id;
  final String name;
  final String? email;
  final String? jobTitle;
  final String? departmentName;
  final String? avatarPath;

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'email': email,
        'job_title': jobTitle,
        'department_name': departmentName,
        'avatar_path': avatarPath,
      };

  static QolabUser? fromInertiaAuth(Object? auth) {
    if (auth is! Map) return null;
    final user = auth['user'];
    if (user is! Map) return null;
    final name = user['name'];
    if (name is! String || name.isEmpty) return null;
    final id = user['id'];
    return QolabUser(
      id: id is int ? id : int.tryParse(id?.toString() ?? '') ?? 0,
      name: name,
      email: _string(user['email']),
      jobTitle: _string(user['job_title']),
      departmentName: _string(user['department_name']),
      avatarPath: _string(user['avatar_path']),
    );
  }

  static String? _string(Object? v) =>
      v is String && v.isNotEmpty ? v : null;
}

/// Inertia page payload parsing.
///
/// Laravel/Inertia embeds the page JSON in the HTML document as:
///   <script data-page="app" type="application/json">{...}</script>
/// Responses to X-Inertia requests return the JSON document directly.
class InertiaPage {
  const InertiaPage({required this.component, required this.props});

  final String component;
  final Map<String, dynamic> props;

  static InertiaPage? parse(String body) {
    final trimmed = body.trimLeft();
    if (trimmed.startsWith('{')) {
      final decoded = _tryDecode(trimmed);
      if (decoded is Map && decoded.containsKey('props')) {
        return _fromMap(decoded);
      }
      return null;
    }
    // HTML: find the data-page JSON blob.
    final m = RegExp(r'<script[^>]*\bdata-page\b[^>]*>(.*?)</script>',
            multiLine: true, dotAll: true)
        .firstMatch(body);
    if (m == null) return null;
    final decoded = _tryDecode(m.group(1)!.trim());
    if (decoded is Map && decoded.containsKey('props')) {
      return _fromMap(decoded);
    }
    return null;
  }

  static InertiaPage? _fromMap(Map map) {
    final component = map['component'];
    final props = map['props'];
    if (component is! String || props is! Map) return null;
    return InertiaPage(
      component: component,
      props: Map<String, dynamic>.from(props),
    );
  }

  QolabUser? get authUser => QolabUser.fromInertiaAuth(props['auth']);

  static Object? _tryDecode(String s) {
    try {
      return jsonDecode(s);
    } catch (_) {
      return null;
    }
  }
}
