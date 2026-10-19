import 'package:flutter/material.dart';

/// Shared "coming soon" scaffold for destinations that are approved for
/// navigation but not yet implemented natively (Chat, Ask Drytis).
class ComingSoonScreen extends StatelessWidget {
  const ComingSoonScreen({
    super.key,
    required this.title,
    required this.description,
    required this.icon,
    this.iconColor = QolabColors.wine,
  });

  final String title;
  final String description;
  final IconData icon;
  final Color iconColor;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: QolabColors.canvas,
      appBar: AppBar(
        backgroundColor: QolabColors.canvas,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        title: Text(title,
            style: const TextStyle(
                color: QolabColors.ink, fontWeight: FontWeight.w700)),
      ),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Container(
                width: 72,
                height: 72,
                decoration: BoxDecoration(
                  color: QolabColors.surface,
                  shape: BoxShape.circle,
                  border: Border.all(color: QolabColors.line),
                ),
                child: Icon(icon, size: 32, color: iconColor),
              ),
              const SizedBox(height: 20),
              Text(title, style: QolabText.title),
              const SizedBox(height: 8),
              Text(
                description,
                textAlign: TextAlign.center,
                style: QolabText.caption,
              ),
              const SizedBox(height: 8),
              const Text('Coming soon', style: QolabText.caption),
            ],
          ),
        ),
      ),
    );
  }
}

// Local aliases so this file stays self-contained without importing
// theme.dart under a different name in tests.
class QolabColors {
  QolabColors._();
  static const canvas = Color(0xFFF7F4EC);
  static const surface = Color(0xFFFFFDF8);
  static const wine = Color(0xFF8B1E2D);
  static const ink = Color(0xFF1A1A1A);
  static const textSoft = Color(0xFF7A756D);
  static const line = Color(0xFFE7E1D4);
}

class QolabText {
  QolabText._();
  static const title = TextStyle(
      color: QolabColors.ink, fontSize: 18, fontWeight: FontWeight.w600);
  static const caption = TextStyle(
      color: QolabColors.textSoft, fontSize: 13.5);
}
