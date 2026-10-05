package dev.drytis.qolab

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.Room

import io.livekit.android.room.track.RemoteVideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import livekit.org.webrtc.RendererCommon

/**
 * v1.5.33 — native meeting room on LiveKit, using ONLY credentials the
 * existing backend issued via POST /meetings/{id}/join-token → {token,url}.
 * Nothing here mints tokens, invents room names, or decides permissions —
 * the Drytis Meeting API does all of that server-side.
 *
 * Contract-supported controls exposed: microphone, camera, speaker/audio
 * (SDK audio handling), participant list/state, Leave (POST
 * /meetings/{id}/leave). Screen share is on the token but not in this UI.
 */
class LiveRoomActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TOKEN = "dev.drytis.qolab.TOKEN"
        const val EXTRA_SERVER_URL = "dev.drytis.qolab.SERVER_URL"
        const val EXTRA_MEETING_ID = "dev.drytis.qolab.MEETING_ID"
        const val EXTRA_MEETING_TITLE = "dev.drytis.qolab.MEETING_TITLE"
        private const val PERM_REQ = 4101
    }

    private val transport by lazy { QolabWebViewTransport.get() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var token: String? = null
    private var serverUrl: String? = null
    private var meetingId: Long = 0
    private var meetingTitle: String? = null
    private var leaving = false

    private var room: Room? = null
    private var micOn = false
    private var camOn = false

    private lateinit var grid: LinearLayout
    private lateinit var localFrame: FrameLayout
    private lateinit var connecting: View
    private lateinit var controlBar: LinearLayout
    private lateinit var peopleList: LinearLayout
    private lateinit var peopleCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent.getStringExtra(EXTRA_TOKEN)
        serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)
        meetingId = intent.getLongExtra(EXTRA_MEETING_ID, 0)
        meetingTitle = intent.getStringExtra(EXTRA_MEETING_TITLE)

        setContentView(buildUi())

        if (token.isNullOrEmpty() || serverUrl.isNullOrEmpty()) {
            Toast.makeText(this, R.string.live_missing_creds, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (!hasPerms()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA),
                PERM_REQ
            )
            return
        }
        connect()
    }

    // ── UI ────────────────────────────────────────────────────────────

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.qm_ink))
            fitsSystemWindows = true
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(8))
        }
        header.addView(TextView(this).apply {
            text = meetingTitle ?: getString(R.string.live_room)
            setTextColor(getColor(R.color.qm_cream_on_wine))
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val statusLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusLine.addView(TextView(this).apply {
            text = "●"
            setTextColor(getColor(R.color.qm_wine))
            textSize = 10f
        })
        statusLine.addView(TextView(this).apply {
            text = "  ${getString(R.string.live_status_live)}   "
            setTextColor(getColor(R.color.qm_cream_on_wine))
            textSize = 12f
        })
        peopleCount = TextView(this).apply {
            setTextColor(getColor(R.color.qm_muted))
            textSize = 12f
        }
        statusLine.addView(peopleCount)
        header.addView(statusLine)
        root.addView(header)

        grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        localFrame = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        grid.addView(localFrame)
        root.addView(grid)

        connecting = TextView(this).apply {
            setText(R.string.live_connecting)
            setTextColor(getColor(R.color.qm_cream_on_wine))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(24))
        }
        root.addView(connecting)

        peopleList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xEE1E1B18.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
        }
        peopleList.addView(TextView(this).apply {
            setText(R.string.live_in_this_meeting)
            setTextColor(getColor(R.color.qm_cream_on_wine))
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        root.addView(
            peopleList,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(14))
            setBackgroundColor(0xFF1E1B18.toInt())
        }
        root.addView(
            controlBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        renderControls()
        return root
    }

    private fun renderControls() {
        controlBar.removeAllViews()
        controlBar.addView(chip(if (micOn) R.string.live_mic_on else R.string.live_mic_off) { toggleMic() })
        controlBar.addView(chip(if (camOn) R.string.live_cam_on else R.string.live_cam_off) { toggleCam() })
        controlBar.addView(chip(R.string.live_people) { togglePeople() })
        controlBar.addView(chip(R.string.live_leave) { leave() })
    }

    private fun chip(labelRes: Int, onClick: (View) -> Unit): View =
        TextView(this).apply {
            setText(labelRes)
            setTextColor(getColor(R.color.qm_cream_on_wine))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundResource(R.drawable.qm_live_control_chip)
            setOnClickListener { onClick(this) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(6), 0, dp(6), 0) }
        }

    // ── LiveKit ───────────────────────────────────────────────────────

    private fun hasPerms(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun connect() {
        connecting.visibility = View.VISIBLE
        val r = LiveKit.create(applicationContext)
        room = r

        scope.launch {
            // events
            launch {
                r.events.events.collect { ev -> runOnUiThread { onEvent(ev) } }
            }
            try {
                r.connect(serverUrl!!, token!!)
                runOnUiThread {
                    connecting.visibility = View.GONE
                    publishDefaults()
                    refreshPeople()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@LiveRoomActivity,
                        R.string.live_connect_failed,
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        }
    }

    private fun onEvent(ev: RoomEvent) {
        when (ev) {
            is RoomEvent.TrackSubscribed -> {
                val t = ev.track
                if (t is RemoteVideoTrack) onRemoteVideo(t)
                refreshPeople()
            }
            is RoomEvent.ParticipantConnected,
            is RoomEvent.ParticipantDisconnected,
            is RoomEvent.ActiveSpeakersChanged -> refreshPeople()
            is RoomEvent.Disconnected -> {
                if (!leaving) {
                    Toast.makeText(this, R.string.live_disconnected, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
            else -> {}
        }
    }

    private fun publishDefaults() {
        val local = room?.localParticipant ?: return
        scope.launch {
            try {
                local.setMicrophoneEnabled(true)
                runOnUiThread { micOn = true; renderControls() }
            } catch (_: Exception) { }
            attachLocalPreview()
        }
    }

    private fun attachLocalPreview() {
        val local = room?.localParticipant ?: return
        val pub = local.getTrackPublication(io.livekit.android.room.track.Track.Source.CAMERA)
        val track = (pub as? io.livekit.android.room.track.LocalTrackPublication)?.track
        if (track is io.livekit.android.room.track.LocalVideoTrack) {
            runOnUiThread {
                val renderer = makeRenderer(mirror = true)
                localFrame.addView(
                    renderer,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                track.addRenderer(renderer)
            }
        }
    }

    private fun onRemoteVideo(track: RemoteVideoTrack) {
        val slot = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        grid.addView(slot, 1)
        val renderer = makeRenderer(mirror = false)
        slot.addView(
            renderer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        track.addRenderer(renderer)
    }

    private fun makeRenderer(mirror: Boolean): SurfaceViewRenderer =
        SurfaceViewRenderer(this).apply {
            setMirror(mirror)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            init(room!!.lkObjects.eglBase.eglBaseContext, null)
        }

    private fun toggleMic() {
        val local = room?.localParticipant ?: return
        val target = !micOn
        scope.launch {
            try {
                local.setMicrophoneEnabled(target)
                runOnUiThread { micOn = target; renderControls() }
            } catch (_: Exception) { }
        }
    }

    private fun toggleCam() {
        val local = room?.localParticipant ?: return
        val target = !camOn
        scope.launch {
            try {
                local.setCameraEnabled(target)
                runOnUiThread {
                    camOn = target
                    renderControls()
                    if (target) attachLocalPreview()
                }
            } catch (_: Exception) { }
        }
    }

    private fun togglePeople() {
        peopleList.visibility =
            if (peopleList.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        refreshPeople()
    }

    private fun refreshPeople() {
        val r = room ?: return
        val names = ArrayList<String>()
        r.localParticipant?.let { local ->
            val nm: String? = local.name
            val id: String? = local.identity?.value
            names.add(
                nm?.takeIf { it.isNotEmpty() }
                    ?: id?.takeIf { it.isNotEmpty() }
                    ?: getString(R.string.live_you)
            )
        }
        r.remoteParticipants.values.forEach { p ->
            val nm: String? = p.name
            val id: String? = p.identity?.value
            names.add(
                nm?.takeIf { it.isNotEmpty() }
                    ?: id?.takeIf { it.isNotEmpty() }
                    ?: "—"
            )
        }
        peopleCount.text = getString(R.string.live_people_n, names.size)
        if (peopleList.visibility == View.VISIBLE) {
            while (peopleList.childCount > 1) peopleList.removeViewAt(1)
            names.forEach { n ->
                peopleList.addView(TextView(this).apply {
                    text = n
                    setTextColor(getColor(R.color.qm_cream_on_wine))
                    textSize = 13f
                    setPadding(0, dp(4), 0, dp(4))
                })
            }
        }
    }

    // ── leave ─────────────────────────────────────────────────────────

    private fun leave() {
        if (leaving) return
        leaving = true
        try { room?.disconnect() } catch (_: Exception) { }
        transport.postJson("/meetings/$meetingId/leave", "{}") {
            runOnUiThread { finish() }
        }
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 3000)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERM_REQ) return
        if (hasPerms()) connect()
        else {
            Toast.makeText(this, R.string.live_needs_perms, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onDestroy() {
        try { if (leaving) room?.disconnect() } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)
}
