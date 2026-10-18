import 'package:flutter/material.dart';

import '../auth/auth_controller.dart';
import '../theme.dart';

/// Basic authenticated Home: real identity from the Inertia auth.user,
/// logout action. Missing fields are omitted, never invented.
class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key, required this.auth});

  final AuthController auth;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: auth,
      builder: (context, _) {
        final user = auth.user;
        final name = user?.name ?? '';
        final first = name.isEmpty ? 'there' : name.split(' ').first;
        return Scaffold(
          backgroundColor: QolabTheme.canvas,
          appBar: AppBar(
            backgroundColor: QolabTheme.canvas,
            surfaceTintColor: Colors.transparent,
            elevation: 0,
            title: Row(
              children: [
                Container(
                  width: 34,
                  height: 34,
                  decoration: BoxDecoration(
                    color: QolabTheme.wine,
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: const Center(
                    child: Text(
                      'Q',
                      style: TextStyle(
                          color: Colors.white,
                          fontSize: 18,
                          fontWeight: FontWeight.w700),
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                const Text('Qolab',
                    style: TextStyle(
                        color: QolabTheme.ink,
                        fontWeight: FontWeight.bold)),
              ],
            ),
            actions: [
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 8),
                child: auth.busy
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: Center(
                            child: CircularProgressIndicator(
                                strokeWidth: 2.2,
                                color: QolabTheme.wine)),
                      )
                    : TextButton(
                        onPressed: () => auth.logout(),
                        child: const Text('Sign out',
                            style: TextStyle(color: QolabTheme.wine)),
                      ),
              ),
            ],
          ),
          body: SafeArea(
            child: ListView(
              padding: const EdgeInsets.symmetric(
                  horizontal: 24, vertical: 16),
              children: [
                Text('Welcome back, $first 👋', style: QolabTheme.headline),
                const SizedBox(height: 6),
                const Text('You are signed in to Qolab.',
                    style: QolabTheme.caption),
                const SizedBox(height: 24),
                _accountCard(),
              ],
            ),
          ),
        );
      },
    );
  }

  Widget _accountCard() {
    final user = auth.user;
    if (user == null) return const SizedBox.shrink();
    final rows = <_Row>[
      _Row('Name', user.name),
      if (user.email != null) _Row('Email', user.email!),
      if (user.jobTitle != null) _Row('Designation', user.jobTitle!),
      if (user.departmentName != null)
        _Row('Department', user.departmentName!),
    ];
    return Container(
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: QolabTheme.surface,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: QolabTheme.line),
      ),
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 14, 16, 10),
            child: Row(
              children: [
                CircleAvatar(
                  radius: 22,
                  backgroundColor: QolabTheme.wine,
                  child: Text(
                    _initials(user.name),
                    style: const TextStyle(
                        color: Colors.white, fontWeight: FontWeight.w600),
                  ),
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: Text(user.name,
                      style: QolabTheme.title,
                      overflow: TextOverflow.ellipsis),
                ),
              ],
            ),
          ),
          const Divider(height: 1, color: QolabTheme.line),
          for (final r in rows)
            Padding(
              padding:
                  const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(
                    width: 110,
                    child: Text(r.label, style: QolabTheme.caption),
                  ),
                  Expanded(
                    child: Text(r.value,
                        style: QolabTheme.body),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  String _initials(String name) {
    final parts =
        name.trim().split(RegExp(r'\s+')).where((p) => p.isNotEmpty).toList();
    if (parts.isEmpty) return 'Q';
    if (parts.length == 1) return parts.first[0].toUpperCase();
    return (parts.first[0] + parts.last[0]).toUpperCase();
  }
}

class _Row {
  const _Row(this.label, this.value);
  final String label;
  final String value;
}
