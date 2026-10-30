library;

/// Pure date/time formatting helpers for Chat messages.
///
/// All inputs are timestamps as stored on the model (parsed server strings —
/// `...Z` suffix parses as UTC; naive strings parse as local). Every helper
/// converts to the DEVICE's local timezone before formatting so labels always
/// match what the user sees on their clock.
///
/// Kept free of Flutter imports so the logic is unit-testable in plain
/// `test()` blocks (no binding required).

/// Compact time label for a message bubble, e.g. `14:05` / `2:05 PM`.
String chatTimeLabel(DateTime t) {
  final local = t.toLocal();
  String two(int n) => n.toString().padLeft(2, '0');
  return '${two(local.hour)}:${two(local.minute)}';
}

/// Human day label for date separators: `Today`, `Yesterday`, else
/// `28 October 2026` (unpadded day, full month name, 4-digit year).
String dayLabel(DateTime t) {
  final local = t.toLocal();
  final now = DateTime.now();
  final localDay = DateTime(local.year, local.month, local.day);
  final today = DateTime(now.year, now.month, now.day);
  if (localDay == today) return 'Today';
  if (localDay == today.subtract(const Duration(days: 1))) return 'Yesterday';
  const months = [
    'January', 'February', 'March', 'April', 'May', 'June',
    'July', 'August', 'September', 'October', 'November', 'December',
  ];
  return '${local.day} ${months[local.month - 1]} ${local.year}';
}

/// True when [older] starts a new local calendar day relative to [newer]
/// (i.e. a separator must be rendered above [older]). [newer] null (first
/// message of the conversation) → always true.
bool startsNewDay(DateTime older, DateTime? newer) {
  if (newer == null) return true;
  final a = older.toLocal();
  final b = newer.toLocal();
  return a.year != b.year || a.month != b.month || a.day != b.day;
}
