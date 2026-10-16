import 'package:flutter/material.dart';

import 'auth/auth_controller.dart';
import 'screens/home_screen.dart';
import 'screens/login_screen.dart';
import 'theme.dart';

/// Startup auth gate:
/// - restoring → splash (no indefinite spinner: bounded by timeouts)
/// - signedIn → Home (back stack cleared — cannot navigate back to login)
/// - signedOut → Login
/// - offline → recoverable connection state with Retry (session kept)
class AuthGate extends StatelessWidget {
  const AuthGate({super.key, required this.auth});

  final AuthController auth;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: auth,
      builder: (context, _) {
        switch (auth.status) {
          case AuthStatus.unknown:
          case AuthStatus.restoring:
            return const _Splash();
          case AuthStatus.signedIn:
            return HomeScreen(auth: auth);
          case AuthStatus.offline:
            return _Offline(auth: auth);
          case AuthStatus.signedOut:
          case AuthStatus.signingIn:
            return LoginScreen(auth: auth);
        }
      },
    );
  }
}

class _Splash extends StatelessWidget {
  const _Splash();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: QolabTheme.canvas,
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 64,
              height: 64,
              decoration: BoxDecoration(
                color: QolabTheme.wine,
                borderRadius: BorderRadius.circular(18),
              ),
              child: const Center(
                child: Text('Q',
                    style: TextStyle(
                        color: Colors.white,
                        fontSize: 32,
                        fontWeight: FontWeight.w700)),
              ),
            ),
            const SizedBox(height: 18),
            const SizedBox(
                width: 24,
                height: 24,
                child:
                    CircularProgressIndicator(strokeWidth: 2.2)),
          ],
        ),
      ),
    );
  }
}

class _Offline extends StatelessWidget {
  const _Offline({required this.auth});

  final AuthController auth;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: QolabTheme.canvas,
      body: Center(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.cloud_off,
                  size: 44, color: QolabTheme.textSoft),
              const SizedBox(height: 18),
              const Text('Can\'t reach Qolab',
                  style: QolabTheme.headline, textAlign: TextAlign.center),
              const SizedBox(height: 8),
              const Text(
                'Check your internet connection. Your saved session is kept — we\'ll sign you in once you\'re back online.',
                style: QolabTheme.caption,
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 24),
              SizedBox(
                width: 200,
                height: 48,
                child: ElevatedButton(
                  onPressed: auth.busy ? null : () => auth.restore(),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: QolabTheme.wine,
                    foregroundColor: Colors.white,
                    shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(14)),
                    elevation: 0,
                  ),
                  child: Text(auth.busy ? 'Retrying…' : 'Retry'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
