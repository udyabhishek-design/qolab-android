import 'package:flutter_test/flutter_test.dart';

import 'package:qolab_app/main.dart';

void main() {
  testWidgets('home screen renders app shell', (WidgetTester tester) async {
    await tester.pumpWidget(const QolabApp());

    expect(find.text('Qolab'), findsOneWidget);
    expect(find.text('Qolab Flutter — under construction'), findsOneWidget);
  });
}
