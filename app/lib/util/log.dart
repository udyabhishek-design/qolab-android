import 'package:flutter/foundation.dart';

/// Prefixed diagnostic logging. Output is visible via
/// `adb logcat -s flutter` (debug) / `adb logcat | grep QOLAB`.
/// NEVER pass tokens, credentials, or full URLs through here.
void qolabLog(String message) => debugPrint('QOLAB $message');
