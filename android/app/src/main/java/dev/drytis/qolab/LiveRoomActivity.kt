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
import kotlinx.coroutines.cancel
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
        // v1.5.41: call-style circular toolbar. Icon = ACTUAL LiveKit track
        // state (micOn/camOn are synced from TrackPublication.isMuted via
        // syncTrackState()), so the visuals can never drift from reality.
        controlBar.addView(roundCtrl(
            if (micOn) R.drawable.qm_ic_mic else R.drawable.qm_ic_mic_off,
            if (micOn) CtrlStyle.ACTIVE else CtrlStyle.INACTIVE,
            getString(if (micOn) R.string.live_mic_on else R.string.live_mic_off)
        ) { toggleMic() })
        controlBar.addView(roundCtrl(
            if (camOn) R.drawable.qm_ic_videocam else R.drawable.qm_ic_videocam_off,
            if (camOn) CtrlStyle.ACTIVE else CtrlStyle.INACTIVE,
            getString(if (camOn) R.string.live_cam_on else R.string.live_cam_off)
        ) { toggleCam() })
        controlBar.addView(roundCtrl(
            R.drawable.qm_ic_people, CtrlStyle.ACTIVE,
            getString(R.string.live_people)
        ) { togglePeople() })
        controlBar.addView(roundCtrl(
            R.drawable.qm_ic_call_end, CtrlStyle.LEAVE,
            getString(R.string.live_leave)
        ) { leave() })
    }

    /** Visual style of a circular meeting control. */
    private enum class CtrlStyle { ACTIVE, INACTIVE, LEAVE }

    /**
     * v1.5.41: circular touch-friendly control (52dp) with pressed
     * feedback and a content description for accessibility.
     *  ACTIVE   — white circle, dark icon (mic/camera ON)
     *  INACTIVE — translucent circle, white slashed icon (mic/camera OFF)
     *  LEAVE    — red circle, white hang-up icon
     */
    private fun roundCtrl(
        iconRes: Int,
        style: CtrlStyle,
        desc: String,
        onClick: (View) -> Unit
    ): View =
        android.widget.ImageButton(this).apply {
            setImageResource(iconRes)
            val ink = 0xFF1A1A1A.toInt()
            when (style) {
                CtrlStyle.ACTIVE -> {
                    background = ctrlRipple(0xFFFFFFFF.toInt())
                    setColorFilter(ink)
                }
                CtrlStyle.INACTIVE -> {
                    background = ctrlRipple(0x33FFFFFF.toInt(), border = true)
                    setColorFilter(0xFFFFFFFF.toInt())
                }
                CtrlStyle.LEAVE -> {
                    background = ctrlRipple(0xFFE4202E.toInt())
                    setColorFilter(0xFFFFFFFF.toInt())
                }
            }
            contentDescription = desc
            setOnClickListener { onClick(this) }
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                setMargins(dp(10), 0, dp(10), 0)
            }
        }

    private fun ctrlRipple(fill: Int, border: Boolean = false): android.graphics.drawable.RippleDrawable {
        val shape = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(fill)
            if (border) setStroke(dp(1), 0x66FFFFFF.toInt())
        }
        val mask = android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.OvalShape()).apply {
            paint.color = -1
        }
        return android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33888888.toInt()), shape, mask)
    }

    /**
     * v1.5.41: pull the TRUE mute/publish state from the LiveKit track
     * publications and re-render the toolbar icons. Called after connect,
     * on toggles, and on RoomEvent TrackMuted/Unmuted (including remote
     * or server-driven changes).
     */
    private fun syncTrackState() {
        val local = room?.localParticipant ?: return
        runOnUiThread {
            micOn = local.isMicrophoneEnabled
            camOn = local.isCameraEnabled
            renderControls()
        }
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
                    // v1.5.41: toolbar icons start from real track state
                    syncTrackState()
                    // v1.5.42: camera is NOT auto-published on join — show
                    // the camera-off placeholder immediately instead of an
                    // empty black slot.
                    syncLocalPreview()
                }            } catch (e: Exception) {
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
            is RoomEvent.TrackMuted -> {
                // v1.5.41: track state changed (local toggle, remote, or
                // server) — icons follow the actual track state.
                if (ev.participant is io.livekit.android.room.participant.LocalParticipant) {
                    syncTrackState()
                    // v1.5.42: a muted camera track must also clear the
                    // local preview (frozen-frame fix).
                    syncLocalPreview()
                }
                refreshPeople()
            }
            is RoomEvent.TrackUnmuted -> {
                if (ev.participant is io.livekit.android.room.participant.LocalParticipant) {
                    syncTrackState()
                    syncLocalPreview()
                }
                refreshPeople()
            }
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
                runOnUiThread { syncTrackState() }
            } catch (_: Exception) { }
            runOnUiThread { syncLocalPreview() }
        }
    }

    /**
     * v1.5.42 FIX (camera-off freeze): the local slot is now rendered
     * strictly from the actual camera publication state.
     *   camera published   → EXACTLY ONE live renderer attached
     *   camera unpublished → renderer removed + released (the frozen last
     *                        frame was the v1.5.41 bug: toggling OFF left
     *                        the old SurfaceViewRenderer in place with no
     *                        track feeding it) and a clean "Camera is off"
     *                        placeholder shown instead.
     * Idempotent: re-running with an unchanged state re-attaches nothing
     * (the renderer guard prevents duplicate renderers on repeated
     * Camera ON → OFF → ON cycles).
     */
    private fun syncLocalPreview() {
        val local = room?.localParticipant ?: return
        val pub = local.getTrackPublication(io.livekit.android.room.track.Track.Source.CAMERA)
        val track = (pub as? io.livekit.android.room.track.LocalTrackPublication)?.track
        val videoTrack = track as? io.livekit.android.room.track.LocalVideoTrack
        runOnUiThread {
            if (videoTrack != null) {
                // Camera ON: keep the existing renderer if any (no
                // duplicate attachment), else attach a fresh one.
                if (localFrame.findViewWithTag<io.livekit.android.renderer.SurfaceViewRenderer?>(
                        "local-renderer") == null) {
                    val renderer = makeRenderer(mirror = true)
                    renderer.tag = "local-renderer"
                    localFrame.removeAllViews()
                    localFrame.addView(
                        renderer,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    videoTrack.addRenderer(renderer)
                }
            } else {
                // Camera OFF: remove + RELEASE the renderer (a stale
                // SurfaceViewRenderer with no track displays the last
                // frame forever), then show the placeholder.
                val existing = localFrame.findViewWithTag<io.livekit.android.renderer.SurfaceViewRenderer?>(
                    "local-renderer")
                if (existing != null) {
                    try { existing.release() } catch (_: Exception) { }
                    localFrame.removeAllViews()
                }
                if (localFrame.findViewWithTag<View>("cam-placeholder") == null) {
                    val ph = layoutInflater.inflate(R.layout.live_cam_placeholder, localFrame, false)
                    ph.tag = "cam-placeholder"
                    localFrame.addView(
                        ph,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }
            }
        }
    }

    /** v1.5.41 kept for compatibility; now a thin wrapper over
     *  syncLocalPreview (single source of truth for the local slot). */
    private fun attachLocalPreview() {
        syncLocalPreview()
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
                // v1.5.41: derive the icon from actual track state — the
                // UI can never drift from what LiveKit is really doing.
                syncTrackState()
            } catch (_: Exception) { }
        }
    }

    private fun toggleCam() {
        val local = room?.localParticipant ?: return
        val target = !camOn
        scope.launch {
            try {
                local.setCameraEnabled(target)
                syncTrackState()
                // v1.5.42: single source of truth — syncLocalPreview decides
                // from the publication state whether the live renderer
                // attaches (ON) or is removed+released with a placeholder
                // (OFF). Called AFTER the state change settles.
                syncLocalPreview()
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

    // v1.5.35: system back = the same contract path as the Leave chip —
    // disconnect + POST /meetings/{id}/leave — never a silent exit.
    override fun onBackPressed() {
        leave()
    }

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
        // v1.5.35 stability: disconnect UNCONDITIONALLY. Back-press used to
        // skip leave() entirely — the room stayed connected (HOT MIC) and no
        // /leave POST fired, stranding the meeting as live (see memory
        // stranded-live-meetings-incident). Also release the coroutine scope
        // (events collector leaked) and any SurfaceViewRenderers.
        if (!leaving) {
            leaving = true
            try { room?.disconnect() } catch (_: Exception) { }
        }
        try { scope.cancel() } catch (_: Exception) { }
        try {
            val root = findViewById<android.view.ViewGroup>(android.R.id.content)
            releaseRenderers(root)
        } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun releaseRenderers(group: android.view.ViewGroup?) {
        group ?: return
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is io.livekit.android.renderer.SurfaceViewRenderer) {
                try { child.release() } catch (_: Exception) { }
            } else if (child is android.view.ViewGroup) {
                releaseRenderers(child)
            }
        }
    }

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)
}
