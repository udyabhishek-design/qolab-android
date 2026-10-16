import 'package:flutter_test/flutter_test.dart';
import 'package:qolab_app/api/inertia.dart';

void main() {
  group('InertiaPage.parse', () {
    test('parses HTML page with data-page JSON and extracts auth.user', () {
      const html = '''
<!DOCTYPE html><html><head><title>Dashboard</title></head><body>
<div id="app" data-page="app"></div>
<script data-page="app" type="application/json">{"component":"Dashboard","props":{"auth":{"user":{"id":7,"name":"Jane Doe","email":"jane@qolab.test","job_title":"Engineer","department_name":"Engineering"}}},"url":"/dashboard","version":"abc"}</script>
<script src="/js/app.js"></script>
</body></html>''';
      final page = InertiaPage.parse(html);
      expect(page, isNotNull);
      expect(page!.component, 'Dashboard');
      final u = page.authUser!;
      expect(u.id, 7);
      expect(u.name, 'Jane Doe');
      expect(u.email, 'jane@qolab.test');
      expect(u.jobTitle, 'Engineer');
      expect(u.departmentName, 'Engineering');
    });

    test('parses raw JSON document (X-Inertia response)', () {
      const json =
          '{"component":"Dashboard","props":{"auth":{"user":{"id":3,"name":"Bob"}}},"url":"/dashboard"}';
      final page = InertiaPage.parse(json);
      expect(page!.authUser!.name, 'Bob');
    });

    test('omits missing optional fields instead of inventing them', () {
      const json =
          '{"component":"Dashboard","props":{"auth":{"user":{"id":1,"name":"Min"}}}}';
      final u = InertiaPage.parse(json)!.authUser!;
      expect(u.email, isNull);
      expect(u.jobTitle, isNull);
    });

    test('auth.user absent → null user (partial auth)', () {
      const json =
          '{"component":"Dashboard","props":{"auth":{"user":null}}}';
      expect(InertiaPage.parse(json)!.authUser, isNull);
    });

    test('non-Inertia HTML → null', () {
      expect(InertiaPage.parse('<html><body>plain</body></html>'), isNull);
    });

    test('garbage JSON inside data-page → null', () {
      const html =
          '<script data-page="app" type="application/json">not json</script>';
      expect(InertiaPage.parse(html), isNull);
    });
  });
}
