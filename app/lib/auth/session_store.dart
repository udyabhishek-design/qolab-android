import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../api/cookie_jar.dart';
import '../api/inertia.dart';

/// Persistence for the session cookie jar + cached identity.
/// Secure implementation wraps flutter_secure_storage; tests use
/// [MemorySessionStore].
abstract class SessionStore {
  Future<void> save({required CookieJar jar, required QolabUser user});
  Future<({CookieJar jar, QolabUser user})?> load();
  Future<void> clear();
}

class SecureSessionStore implements SessionStore {
  SecureSessionStore({FlutterSecureStorage? storage})
      : _storage = storage ?? const FlutterSecureStorage();

  final FlutterSecureStorage _storage;

  static const _kJar = 'qolab.session.jar';
  static const _kUser = 'qolab.session.user';

  @override
  Future<void> save({required CookieJar jar, required QolabUser user}) async {
    await _storage.write(
        key: _kJar, value: jsonEncode(jar.toPersistJson()));
    await _storage.write(key: _kUser, value: jsonEncode(user.toJson()));
  }

  @override
  Future<({CookieJar jar, QolabUser user})?> load() async {
    final jarRaw = await _storage.read(key: _kJar);
    final userRaw = await _storage.read(key: _kUser);
    if (jarRaw == null || userRaw == null) return null;
    try {
      final jar = CookieJar.fromPersistJson(
          Map<String, dynamic>.from(jsonDecode(jarRaw) as Map));
      final userMap =
          Map<String, dynamic>.from(jsonDecode(userRaw) as Map);
      final user = QolabUser(
        id: userMap['id'] as int,
        name: userMap['name'] as String,
        email: userMap['email'] as String?,
        jobTitle: userMap['job_title'] as String?,
        departmentName: userMap['department_name'] as String?,
        avatarPath: userMap['avatar_path'] as String?,
      );
      return (jar: jar, user: user);
    } catch (_) {
      // Corrupt storage — treat as signed out.
      await clear();
      return null;
    }
  }

  @override
  Future<void> clear() async {
    await _storage.delete(key: _kJar);
    await _storage.delete(key: _kUser);
  }
}

class MemorySessionStore implements SessionStore {
  String? _jar;
  String? _user;

  @override
  Future<void> save({required CookieJar jar, required QolabUser user}) async {
    _jar = jsonEncode(jar.toPersistJson());
    _user = jsonEncode(user.toJson());
  }

  @override
  Future<({CookieJar jar, QolabUser user})?> load() async {
    if (_jar == null || _user == null) return null;
    final jar = CookieJar.fromPersistJson(
        Map<String, dynamic>.from(jsonDecode(_jar!) as Map));
    final userMap = Map<String, dynamic>.from(jsonDecode(_user!) as Map);
    final user = QolabUser(
      id: userMap['id'] as int,
      name: userMap['name'] as String,
      email: userMap['email'] as String?,
      jobTitle: userMap['job_title'] as String?,
      departmentName: userMap['department_name'] as String?,
      avatarPath: userMap['avatar_path'] as String?,
    );
    return (jar: jar, user: user);
  }

  @override
  Future<void> clear() async {
    _jar = null;
    _user = null;
  }
}
