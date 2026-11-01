package dev.qolab.qolab_app

import android.content.Intent
import android.os.Bundle

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * Phase C3A — bridges FCM notification actions (accept/decline) and
 * notification taps into the Flutter engine.
 *
 * Dart registers itself as the "qolab/call_actions" MethodChannel handler
 * on startup and calls `takePending` to receive any action that arrived
 * while the engine was cold (app terminated — notification button press).
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
        // The Dart side (fcm_bridge.dart) creates the MethodChannel on
        // "qolab/call_actions" and assigns CallActionSink.channel through
        // the messenger; nothing to do here beyond keeping the engine warm.
        CallActionSink.pending?.let { pending ->
            CallActionSink.pending = null
            CallActionSink.dispatch(pending)
        }
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
