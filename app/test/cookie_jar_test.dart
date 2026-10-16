import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:qolab_app/api/cookie_jar.dart';

void main() {
  group('CookieJar', () {
    test('captures multiple Set-Cookie headers', () {
      final jar = CookieJar();
      jar.storeFrom([
        'qolab-session=abc123; path=/; httponly; samesite=lax',
        'XSRF-TOKEN=tok%3D%3D; path=/; samesite=lax',
      ]);
      expect(jar.length, 2);
      expect(jar['qolab-session']?.value, 'abc123');
      expect(jar['XSRF-TOKEN']?.value, 'tok%3D%3D');
    });

    test('rotated session cookie replaces the old value', () {
      final jar = CookieJar();
      jar.storeFrom(['qolab-session=old; path=/']);
      jar.storeFrom(['qolab-session=new-rotated; path=/']);
      expect(jar['qolab-session']?.value, 'new-rotated');
      expect(jar.length, 1);
    });

    test('deletion: empty value removes cookie; past expiry removes cookie',
        () {
      final jar = CookieJar();
      jar.storeFrom(['qolab-session=v; path=/']);
      jar.storeFrom(['qolab-session=; path=/']);
      expect(jar['qolab-session'], isNull);

      jar.storeFrom(['a=1; path=/']);
      jar.storeFrom(['a=x; expires=Thu, 01 Jan 1970 00:00:00 GMT']);
      expect(jar['a'], isNull);
    });

    test('Max-Age expiry prunes the cookie', () {
      final jar = CookieJar();
      jar.storeFrom(['qolab-session=v; Max-Age=0']);
      expect(jar['qolab-session'], isNull);
      jar.storeFrom(['b=2; Max-Age=3600']);
      expect(jar['b']?.value, '2');
    });

    test('cookie header joins unexpired cookies', () {
      final jar = CookieJar();
      jar.storeFrom([
        'qolab-session=s1; path=/',
        'XSRF-TOKEN=x%3Dy; path=/',
      ]);
      expect(jar.cookieHeader(),
          'qolab-session=s1; XSRF-TOKEN=x%3Dy');
    });

    test('hasSessionCookie checks Laravel session name', () {
      final jar = CookieJar();
      expect(jar.hasSessionCookie, isFalse);
      jar.storeFrom(['XSRF-TOKEN=t; path=/']);
      expect(jar.hasSessionCookie, isFalse);
      jar.storeFrom(['qolab-session=s; path=/']);
      expect(jar.hasSessionCookie, isTrue);
    });

    test('value containing "=" splits on first separator only', () {
      final jar = CookieJar();
      jar.storeFrom(['weird=a=b=c; path=/']);
      expect(jar['weird']?.value, 'a=b=c');
    });

    test('persists to JSON and back, dropping expired entries', () {
      final jar = CookieJar();
      jar.storeFrom([
        'qolab-session=s; path=/',
        'dead=d; Max-Age=0',
      ]);
      final json = jsonEncode(jar.toPersistJson());
      final restored =
          CookieJar.fromPersistJson(jsonDecode(json) as Map<String, dynamic>);
      expect(restored['qolab-session']?.value, 's');
      expect(restored['dead'], isNull);
    });

    test('adoptFrom replaces contents entirely', () {
      final a = CookieJar()..storeFrom(['x=1; path=/', 'qolab-session=s; path=/']);
      final b = CookieJar()..storeFrom(['y=2; path=/']);
      b.adoptFrom(a);
      expect(b['y'], isNull);
      expect(b['qolab-session']?.value, 's');
    });
  });

  test('HTTP date parser handles RFC 7231 format', () {
    final d = HttpDateParser.tryParse('Fri, 16 Oct 2026 19:00:00 GMT');
    expect(d, isNotNull);
    expect(d!.year, 2026);
    expect(d.month, 10);
    final past = HttpDateParser.tryParse('Thu, 01 Jan 1970 00:00:00 GMT');
    expect(DateTime.now().isAfter(past!), isTrue);
  });
}
