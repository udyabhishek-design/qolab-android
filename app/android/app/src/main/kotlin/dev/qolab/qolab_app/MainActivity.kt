package dev.qolab.qolab_app

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.util.Log

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * Phase C3A — bridges FCM notification actions (accept/decline) and
 * notification taps into the Flutter engine.
 *
 * v1.5.62: MainActivity OWNS the native half of the "qolab/call_actions"
 * MethodChannel. It sets itself as a MethodCallHandler when the engine
 * is configured, so Dart's `takePending` invokeMethod always has a native
 * receiver (v1.5.61 shipped no native handler → MissingPluginException),
 * and wires CallActionSink.channel so live (already-running-app) actions
 * are delivered via callAction instead of being parked as "pending".
 *
 * Dart registers a MethodCallHandler on its end at startup
 * (fcm_bridge.dart) and calls `takePending` to receive any action that
 * arrived while the engine was cold (app terminated — notification
 * button press).
 *
 * v1.5.64: also owns the native half of "qolab/ringback" — a repeating
 * outgoing-call ringback tone (ToneGenerator on STREAM_VOICE_CALL) for
 * the caller while the recipient's device rings. Call-volume behaviour,
 * no audio-focus contention with the incoming-call ringtone.
 */
object CallActionSink {
    @Volatile
    var channel: MethodChannel? = null

    /** Action captured before the Dart handler existed. */
    @Volatile
    var pending: Bundle? = null

    fun dispatch(data: Bundle) {
        val ch = channel
        if (ch != null) {
            ch.invokeMethod("callAction", bundleToMap(data))
        } else {
            pending = data
        }
    }

    fun bundleToMap(b: Bundle): Map<String, String?> {
        val m = HashMap<String, String?>()
        for (key in b.keySet()) {
            m[key] = b.getString(key)
        }
        return m
    }
}

/** Native ringback for outgoing calls (v1.5.64). */
object Ringback {
    private const val TAG = "QolabRingback"
    private const val TONE_VOLUME = 80
    private const val TONE_DURATION_MS = 400

    private var tone: ToneGenerator? = null

    fun start() {
        try {
            stop()
            tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, TONE_VOLUME).also { gen ->
                gen.startTone(ToneGenerator.TONE_SUP_RINGTONE, TONE_DURATION_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "start failed: ${e.message}")
        }
    }

    fun stop() {
        try {
            tone?.stopTone()
            tone?.release()
        } catch (_: Exception) {
        }
        tone = null
    }
}

class MainActivity : FlutterActivity() {

    companion object {
        private val CALL_ACTIONS = setOf(
            CallActionReceiver.ACTION_ACCEPT_CALL,
            CallActionReceiver.ACTION_DECLINE_CALL,
            CallActionReceiver.ACTION_INCOMING_CALL,
        )
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // Native half of the "qolab/call_actions" channel. Registered here,
        // per-engine, before the Dart side asks for takePending.
        val channel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "qolab/call_actions"
        )
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                // Dart (fcm_bridge.dart) is up and listening: hand over
                // any action parked while the engine was cold.
                "takePending" -> {
                    val pendingBundle = CallActionSink.pending
                    CallActionSink.pending = null
                    if (pendingBundle != null) {
                        result.success(CallActionSink.bundleToMap(pendingBundle))
                    } else {
                        result.success(null)
                    }
                }
                // Dart acknowledges it is ready to receive live actions.
                "ready" -> {
                    CallActionSink.channel = channel
                    result.success(null)
                }
                // Keep the live dispatch channel from leaking a dead
                // engine's channel when the activity is destroyed.
                "detach" -> {
                    if (CallActionSink.channel === channel) {
                        CallActionSink.channel = null
                    }
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
        CallActionSink.channel = channel
        CallActionSink.pending?.let { pending ->
            CallActionSink.pending = null
            CallActionSink.dispatch(pending)
        }

        // Native half of "qolab/ringback" (v1.5.64).
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "qolab/ringback"
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "start" -> {
                    Ringback.start()
                    result.success(null)
                }
                "stop" -> {
                    Ringback.stop()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        CallActionSink.channel = null
        // Activity/engine teardown: never leave a ringback tone running.
        Ringback.stop()
        super.cleanUpFlutterEngine(flutterEngine)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureCallAction(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureCallAction(intent)
    }

    private fun captureCallAction(intent: Intent?) {
        val action = intent?.action ?: return
        if (action in CALL_ACTIONS) {
            CallActionSink.dispatch(Bundle().apply {
                putString("action", action)
                putString(CallActionReceiver.EXTRA_CALL_ID,
                    intent.getStringExtra(CallActionReceiver.EXTRA_CALL_ID))
                putString(CallActionReceiver.EXTRA_CALL_TYPE,
                    intent.getStringExtra(CallActionReceiver.EXTRA_CALL_TYPE))
                putString(CallActionReceiver.EXTRA_CALLER_ID,
                    intent.getStringExtra(CallActionReceiver.EXTRA_CALLER_ID))
                putString(CallActionReceiver.EXTRA_CALLER_NAME,
                    intent.getStringExtra(CallActionReceiver.EXTRA_CALLER_NAME))
            })
        }
    }
}
