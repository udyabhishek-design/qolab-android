import 'package:flutter/material.dart';

void main() {
  runApp(const QolabApp());
}

/// Qolab Flutter app shell (greenfield, option B).
///
/// Minimal placeholder home screen for the first CI build; screens and
/// navigation arrive with the first feature tickets.
class QolabApp extends StatelessWidget {
  const QolabApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Qolab',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF8B1E2D)),
        scaffoldBackgroundColor: const Color(0xFFF7F4EC),
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatelessWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: const Color(0xFFF7F4EC),
        title: const Text(
          'Qolab',
          style: TextStyle(
            color: Color(0xFF1A1A1A),
            fontWeight: FontWeight.bold,
          ),
        ),
      ),
      body: const Center(
        child: Text(
          'Qolab Flutter — under construction',
          style: TextStyle(color: Color(0xFF7A756D)),
        ),
      ),
    );
  }
}
