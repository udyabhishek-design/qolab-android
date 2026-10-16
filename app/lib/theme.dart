import 'package:flutter/material.dart';

/// Qolab warm-light design tokens (mirrors the approved mobile direction).
class QolabTheme {
  QolabTheme._();

  static const canvas = Color(0xFFF7F4EC);
  static const surface = Color(0xFFFFFDF8);
  static const wine = Color(0xFF8B1E2D);
  static const wineFaded = Color(0xFFB98A93);
  static const ink = Color(0xFF1A1A1A);
  static const textSoft = Color(0xFF7A756D);
  static const line = Color(0xFFE7E1D4);

  static const headline = TextStyle(
      color: ink, fontSize: 26, fontWeight: FontWeight.w700);

  static const title = TextStyle(
      color: ink, fontSize: 18, fontWeight: FontWeight.w600);

  static const body = TextStyle(color: ink, fontSize: 15);

  static const caption = TextStyle(color: textSoft, fontSize: 13.5);

  static InputDecoration field(
      {required String label, String? hint, Widget? suffix}) {
    return InputDecoration(
      labelText: label,
      hintText: hint,
      suffixIcon: suffix,
      filled: true,
      fillColor: surface,
      labelStyle: const TextStyle(color: textSoft),
      hintStyle: const TextStyle(color: Color(0xFFB8B2A6)),
      contentPadding:
          const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: const BorderSide(color: line),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: const BorderSide(color: wine, width: 1.4),
      ),
      errorBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: const BorderSide(color: Color(0xFFC0392B)),
      ),
      focusedErrorBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: const BorderSide(color: Color(0xFFC0392B), width: 1.4),
      ),
    );
  }
}
