import 'dart:async';

import 'package:flutter/services.dart';

import '../util/log.dart';

/// v1.5.64 — conventional repeating telephone ringback for the OUTGOING
/// caller while the recipient's device rings.
///
/// Android telephony convention: 2s cadence — 400ms dual tone
/// (440Hz + 480Hz) followed by 1600ms silence, repeating. Implement
/// via ToneGenerator on the STREAM_VOICE_CALL audio stream:
///   - correct volume behaviour (follows the call/system volume, not the
///     ringtone volume),
///   - no audio-focus fight with an in-call MediaPlayer (the tone stops
///     before any media plays).
///
/// Single instance semantics: [start] while already playing restarts the
/// cadence safely; [stop] is idempotent and ALWAYS silences immediately,
/// whatever the terminal reason (answered / declined / cancelled /
/// missed / failed).
class RingbackTone {
  static const _channel = MethodChannel('qolab/ringback');
  static Timer? _timer;
  static bool _playing = false;

  /// Begin the repeating ringback cadence. Safe to call repeatedly.
  static Future<void> start() async {
    if (_playing) return;
    _playing = true;
    qolabLog('ringback: start');
    await _beep();
    _timer?.cancel();
    _timer = Timer.periodic(const Duration(seconds: 2), (_) async {
      if (!_playing) return;
      await _beep();
    });
  }

  /// Stop immediately and unconditionally.
  static Future<void> stop() async {
    if (!_playing && _timer == null) return;
    _playing = false;
    _timer?.cancel();
    _timer = null;
    qolabLog('ringback: stop');
    try {
      await _channel.invokeMethod<void>('stop');
    } catch (_) {
      // No native half (tests / future platforms) — nothing to stop.
    }
  }

  static bool get isPlaying => _playing;

  static Future<void> _beep() async {
    try {
      await _channel.invokeMethod<void>('start');
    } catch (_) {
      // Native half unavailable: fall back to the system alert tone so
      // the caller still gets audible feedback.
      await SystemSound.play(SystemSoundType.alert);
    }
  }
}
