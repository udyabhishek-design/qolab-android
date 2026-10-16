package dev.drytis.qolab

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
        /** v1.5.49: Development-only diagnostic logging tag. Never logs
         *  tokens, credentials or meeting content — identities and state
         *  transitions only. */
        private const val TAG = "QolabLive"
    }

    private val transport by lazy { QolabWebViewTransport.get() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var token: String? = null
    private var serverUrl: String? = null
    private var meetingId: Long = 0
    private var meetingTitle: String? = null
    private var leaving = false

    // v1.5.49: activity lifecycle guard — no renderer work after destroy.
    private var destroyed = false

    // v1.5.50: Room.release() runs exactly once (shutdownRoom()).
    private var released = false

    private var room: Room? = null
    private var micOn = false
    private var camOn = false

    // v1.5.49: renderer → owning VideoTrack registry. LiveKit's
    // VideoTrack keeps a VideoSink list; releasing a SurfaceViewRenderer
    // WITHOUT removeRenderer() leaves a dead sink registered on the track
    // — the v1.5.48 multi-participant freeze (frames pushed into a
    // released EGL surface). Every teardown must go through
    // destroyRenderer() which removes the sink BEFORE release.
    private val rendererTracks =
        java.util.concurrent.ConcurrentHashMap<io.livekit.android.renderer.SurfaceViewRenderer,
                io.livekit.android.room.track.VideoTrack>()


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
        // v1.5.50 FIX (grid starvation): localFrame previously used
        // MATCH_PARENT height with NO weight in a VERTICAL LinearLayout —
        // it consumed the entire grid and every remote tile (height 0,
        // weight 1f) measured to ZERO pixels. Confirmed cause of "second
        // participant does not appear". Weighted children now share the
        // grid equally and redistribute automatically when tiles are
        // added/removed (1 participant → local fills; 2 → ~equal halves;
        // 3–4 → practical single-column grid).
        localFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
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
                    // v1.5.49: participants already in the room when we
                    // joined never fire ParticipantConnected — seed their
                    // tiles (with video when already publishing) now.
                    seedExistingParticipants()
                    refreshPeople()
                    // v1.5.41: toolbar icons start from real track state
                    syncTrackState()
                    // v1.5.42: camera is NOT auto-published on join — show
                    // the camera-off placeholder immediately instead of an
                    // empty black slot.
                    syncLocalPreview()
                    Log.i(TAG, "connected to room, remote participants=${r.remoteParticipants.size}")
                }            } catch (e: Exception) {
                Log.w(TAG, "connect failed: ${e.message}", e)
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
                if (t is RemoteVideoTrack) {
                    // v1.5.49: skip only the video work when identity is
                    // unknown — refreshPeople() must still run below.
                    ev.participant.identity?.value?.let { id ->
                        onRemoteVideo(
                            t,
                            id,
                            ev.participant.name,
                            alreadyMuted = ev.publication?.muted ?: false
                        )
                    }
                }
                refreshPeople()
            }
            is RoomEvent.ParticipantConnected -> {
                // v1.5.49: the participant tile exists from the moment
                // they join — camera state only affects the renderer
                // inside it, never the tile itself.
                ev.participant.identity?.value?.let { ensureRemoteTile(it) }
                refreshPeople()
            }
            is RoomEvent.ParticipantDisconnected -> {
                // v1.5.48: drop the remote tile when they leave — no
                // orphaned renderer surfaces for departed participants.
                ev.participant.identity?.value?.let { onRemoteGone(it) }
                refreshPeople()
            }
            is RoomEvent.ActiveSpeakersChanged -> refreshPeople()
            is RoomEvent.TrackMuted -> {
                // v1.5.41: track state changed (local toggle, remote, or
                // server) — icons follow the actual track state.
                if (ev.participant is io.livekit.android.room.participant.LocalParticipant) {
                    syncTrackState()
                    // v1.5.42: a muted camera track must also clear the
                    // local preview (frozen-frame fix).
                    syncLocalPreview()
                } else if (isRemoteCameraVideo(ev.publication)) {
                    // v1.5.51: consistent type+source classification (the
                    // v1.5.50 source-only gate missed UNKNOWN-source
                    // publications → frozen last frame). Remote camera OFF —
                    // no frozen frame for them on our screen either.
                    ev.participant.identity?.value?.let { id ->
                        onRemoteCameraOff(id, ev.participant.name)
                    }
                }
                refreshPeople()
            }
            is RoomEvent.TrackUnmuted -> {
                if (ev.participant is io.livekit.android.room.participant.LocalParticipant) {
                    syncTrackState()
                    syncLocalPreview()
                } else if (isRemoteCameraVideo(ev.publication)) {
                    // v1.5.51: same consistent classification as Muted.
                    // Remote camera back ON — re-attach a fresh renderer
                    // (the old one was released on mute).
                    onRemoteCameraOn(ev)
                }
                refreshPeople()
            }
            is RoomEvent.TrackUnsubscribed -> {
                // v1.5.49: unsubscribe drops the VIDEO renderer but keeps
                // the participant tile + placeholder while they stay
                // connected (mute/unpublish of the camera publication).
                if (ev.track is RemoteVideoTrack) {
                    ev.participant.identity?.value?.let { onRemoteTrackDropped(it) }
                }
                refreshPeople()
            }
            is RoomEvent.TrackUnpublished -> {
                // v1.5.51: unpublish was previously unhandled. When a
                // remote camera track is unpublished (web client camera
                // off can unpublish), detach + release the renderer, KEEP
                // the tile with a camera-off placeholder while the
                // participant remains connected. onRemoteTrackDropped is
                // idempotent, so a following TrackUnsubscribed for the
                // same track cannot double-clean or touch another
                // participant's renderer (identity-keyed slots).
                // (TrackUnpublished exposes ONLY `publication` — the
                // track is gone by the time this event fires.)
                if (ev.participant !is io.livekit.android.room.participant.LocalParticipant &&
                    ev.publication?.track is RemoteVideoTrack
                ) {
                    Log.d(TAG, "remote track unpublished sid=${ev.publication?.sid}")
                    ev.participant.identity?.value?.let { onRemoteTrackDropped(it) }
                }
                refreshPeople()
            }
            is RoomEvent.Disconnected -> {
                // v1.5.50: Disconnected is TERMINAL in 2.18.2 (transient loss
                // arrives as Reconnecting/Reconnected instead). Log with the
                // SDK reason and, for an UNEXPECTED terminal disconnect
                // (server shutdown, room deleted, exhausted reconnect), still
                // POST the existing /leave endpoint so the backend is not left
                // with a stranded participant.
                val reason = ev.reason
                val clientInitiated = leaving
                Log.w("QolabLive", "terminal disconnect reason=$reason clientInitiated=$clientInitiated")
                if (!clientInitiated) {
                    postLeave()
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
            } catch (e: Exception) {
                // v1.5.50: a silent mic failure leaves the toolbar lying
                // (icon shows unmuted while nothing is published). Log it and
                // tell the user; syncTrackState() re-reads the REAL state.
                Log.e("QolabLive", "microphone enable failed", e)
                runOnUiThread {
                    if (destroyed || isFinishing) return@runOnUiThread
                    Toast.makeText(
                        this@LiveRoomActivity,
                        R.string.live_mic_enable_failed,
                        Toast.LENGTH_LONG
                    ).show()
                    syncTrackState()
                }
            }
            runOnUiThread { syncLocalPreview() }
        }
    }

    /**
     * v1.5.48 FIX (frozen frame on camera-off): the local slot is rendered
     * from the publication's MUTED state, not merely from track existence.
     *
     * LiveKit Android 2.18.2 `setCameraEnabled(false)` does NOT unpublish
     * the camera track — it marks the existing publication muted and stops
     * capture while the track stays attached. The v1.5.42 logic keyed on
     * "a track object exists", so camera-OFF kept the renderer attached to
     * a muted, capture-stopped track and the SurfaceViewRenderer retained
     * its final (frozen) frame.
     *
     *   publication unmuted        → EXACTLY ONE live renderer attached
     *   publication muted (OFF)    → renderer removed + released and a
     *                                 clean "Camera is off" placeholder
     *   no publication / no track  → placeholder
     *
     * Idempotent: re-running with an unchanged state re-attaches nothing
     * (the renderer tag-guard prevents duplicate renderers on repeated
     * Camera ON → OFF → ON cycles). The decision runs through
     * [LocalVideoSlotRules] (pure, JVM-tested).
     */
    private fun syncLocalPreview() {
        val local = room?.localParticipant ?: return
        val pub = local.getTrackPublication(io.livekit.android.room.track.Track.Source.CAMERA)
        val ltp = pub as? io.livekit.android.room.track.LocalTrackPublication
        val videoTrack = ltp?.track as? io.livekit.android.room.track.LocalVideoTrack
        val state = LocalVideoSlotRules.localSlot(
            publicationExists = ltp != null,
            trackIsLiveVideo = videoTrack != null,
            publicationMuted = ltp?.muted ?: true
        )
        runOnUiThread {
            if (state == LocalVideoSlotRules.SlotState.LIVE) {
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
                    attachRenderer(videoTrack!!, renderer)
                }
                // Remove any stale placeholder left over from a prior OFF.
                localFrame.findViewWithTag<View>("cam-placeholder")?.let { localFrame.removeView(it) }
            } else {
                // Camera OFF (or no publication): unregister + release the
                // renderer (a muted, capture-stopped track leaves the
                // SurfaceViewRenderer holding its final frame forever),
                // then show the placeholder.
                val existing = localFrame.findViewWithTag<io.livekit.android.renderer.SurfaceViewRenderer?>(
                    "local-renderer")
                if (existing != null) {
                    destroyRenderer(existing)
                    localFrame.removeView(existing)
                }
                if (localFrame.findViewWithTag<View>("cam-placeholder") == null) {
                    // v1.5.51: local tile uses the same visible
                    // avatar/name placeholder as remote tiles; the local
                    // participant's name comes from LiveKit when set.
                    val localName = room?.localParticipant?.name
                    localFrame.addView(
                        makeTilePlaceholder(localName),
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

    /**
     * v1.5.48: remote slots are keyed by participant identity so the same
     * principles as the local fix apply: a muted remote publication (their
     * camera OFF) must not keep a frozen frame on our screen, and an
     * unsubscribed/gone track must drop the slot entirely.
     *
     * v1.5.49: participant tiles now exist INDEPENDENTLY of camera state —
     * [ensureRemoteTile] creates an identity-keyed placeholder tile on
     * ParticipantConnected and in the post-connect sweep of existing
     * participants; video renderers attach/detach inside it as the camera
     * publishes/mutes/unsubscribes.
     */
    /**
     * v1.5.51: builds the VISIBLE camera-off placeholder (avatar
     * initial + display name + camera-off caption) used in every local
     * and remote tile. Replaces the near-invisible v1.5.50 TextView
     * (#331A1A1A on #1A1A1A) — two camera-off participants now read as
     * two distinct tiles.
     */
    private fun makeTilePlaceholder(displayName: String?): View =
        layoutInflater.inflate(R.layout.live_tile_placeholder, null, false).apply {
            tag = "cam-placeholder"
            val content = RemoteCameraEventRules.placeholderFor(
                displayName,
                getString(R.string.live_tile_you),
                getString(R.string.live_tile_camera_off)
            )
            findViewById<TextView>(R.id.tile_avatar).text = content.initial
            findViewById<TextView>(R.id.tile_name).text = content.name
            findViewById<TextView>(R.id.tile_caption).text = content.caption
        }

    /**
     * v1.5.51: single consistent remote-camera-video predicate. The
     * v1.5.50 defect: TrackSubscribed keyed on TRACK TYPE while
     * TrackMuted/TrackUnmuted keyed only on publication.source ==
     * CAMERA — publications with UNKNOWN/missing source (web peers,
     * event-ordering races) attached a renderer on subscribe but never
     * tore it down on mute → frozen last frame. Every remote video
     * branch now classifies through [RemoteCameraEventRules].
     */
    private fun isRemoteCameraVideo(pub: io.livekit.android.room.track.TrackPublication?): Boolean {
        val trackKind = when (pub?.track) {
            is RemoteVideoTrack -> RemoteCameraEventRules.TrackKind.REMOTE_VIDEO
            null -> RemoteCameraEventRules.TrackKind.MISSING
            else -> RemoteCameraEventRules.TrackKind.OTHER
        }
        val source = when (pub?.source) {
            io.livekit.android.room.track.Track.Source.CAMERA -> RemoteCameraEventRules.SourceKind.CAMERA
            io.livekit.android.room.track.Track.Source.MICROPHONE -> RemoteCameraEventRules.SourceKind.MICROPHONE
            io.livekit.android.room.track.Track.Source.SCREEN_SHARE -> RemoteCameraEventRules.SourceKind.SCREEN_SHARE
            io.livekit.android.room.track.Track.Source.UNKNOWN -> RemoteCameraEventRules.SourceKind.UNKNOWN
            null -> RemoteCameraEventRules.SourceKind.MISSING
            else -> RemoteCameraEventRules.SourceKind.UNKNOWN
        }
        Log.d(TAG, "classify pub sid=${pub?.sid} source=$source trackKind=$trackKind")
        return RemoteCameraEventRules.isRemoteCameraVideo(trackKind, source)
    }

    // v1.5.51: identity → display name, so camera-off placeholders can
    // show the participant's name even after their video/track objects
    // are gone (recorded on tile creation / subscribe / people refresh).
    private val remoteNames = HashMap<String, String?>()

    private fun remoteSlotTag(identity: String) = "remote-${identity}"

    /**
     * v1.5.52 FIX (ClassCastException, logcat 2026-10-16 12:42:51):
     * `View.findViewWithTag()` matches the view ITSELF first, and the
     * remote slot (FrameLayout) carries the SAME tag as the renderer
     * placed inside it (both `remoteSlotTag(identity)`), so
     * `slot.findViewWithTag<SurfaceViewRenderer>(tag)` returned the SLOT
     * — an unchecked generic cast that crashed in onRemoteCameraOff()
     * and silently skipped renderer reattachment in onRemoteCameraOn()
     * (non-null slot ≠ no renderer). Scan the slot's DIRECT children
     * (the renderer is always a direct child) with an explicit
     * type+tag check — no unsafe casts.
     */
    private fun findRemoteRenderer(
        slot: FrameLayout,
        tag: String
    ): io.livekit.android.renderer.SurfaceViewRenderer? {
        for (i in 0 until slot.childCount) {
            val child = slot.getChildAt(i)
            if (child is io.livekit.android.renderer.SurfaceViewRenderer && child.tag == tag) {
                return child
            }
        }
        return null
    }

    /**
     * v1.5.49: creates (or returns) the identity-keyed participant tile
     * with a camera-off placeholder. Idempotent — never duplicates a tile,
     * never stacks on resubscribe.
     */
    private fun ensureRemoteTile(identity: String): FrameLayout {
        val tag = remoteSlotTag(identity)
        grid.findViewWithTag<FrameLayout>(tag)?.let { return it }
        val slot = FrameLayout(this).apply {
            this.tag = tag
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        // v1.5.51: visible placeholder with the participant's identity —
        // remembers the name so camera OFF can restore it later.
        slot.addView(makeTilePlaceholder(remoteNames[identity]),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        grid.addView(slot, 1)
        Log.d(TAG, "remote tile created for $identity")
        return slot
    }

    /**
     * v1.5.49: post-connect sweep — participants already in the room when
     * we joined (they never fire ParticipantConnected for us).
     */
    private fun seedExistingParticipants() {
        val r = room ?: return
        r.remoteParticipants.values.forEach { p ->
            val id = p.identity?.value ?: return@forEach
            ensureRemoteTile(id)
            // If their camera is already publishing, attach video now —
            // TrackSubscribed for pre-existing publications may have
            // fired before this activity's grid was ready, or the event
            // ordering placed it before connect() returned.
            val pub = p.getTrackPublication(io.livekit.android.room.track.Track.Source.CAMERA)
            val track = pub?.track as? RemoteVideoTrack
            if (track != null && pub?.muted == false) {
                onRemoteVideo(track, id, p.name, alreadyMuted = false)
            }
        }
    }

    private fun onRemoteVideo(
        track: RemoteVideoTrack,
        identity: String,
        name: String?,
        alreadyMuted: Boolean
    ) {
        val tag = remoteSlotTag(identity)
        // The tile always exists (ensureRemoteTile / ParticipantConnected /
        // post-connect sweep); replace its contents rather than stacking.
        // v1.5.51: remember this participant's display name for later
        // camera-off placeholders.
        remoteNames[identity] = name
        val slot = ensureRemoteTile(identity)
        releaseRenderers(slot)
        slot.removeAllViews()
        val renderer = makeRenderer(mirror = false)
        renderer.tag = tag
        slot.addView(
            renderer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        attachRenderer(track, renderer)
        // The publication may already be muted at subscribe time (camera
        // OFF before we joined) — show the placeholder right away instead
        // of relying on a TrackMuted event that already fired.
        if (LocalVideoSlotRules.remoteSlot(
                trackExists = true,
                publicationMuted = alreadyMuted
            ) == LocalVideoSlotRules.SlotState.PLACEHOLDER
        ) {
            onRemoteCameraOff(identity, name)
        }
    }

    /**
     * v1.5.49: detach a remote slot's renderer (unregistered from its
     * track FIRST — the v1.5.48 dead-sink freeze) and show the shared
     * placeholder in the tile — used when the remote camera publication
     * is muted (their camera OFF).
     */
    private fun onRemoteCameraOff(identity: String, name: String?) {
        val tag = remoteSlotTag(identity)
        val slot = grid.findViewWithTag<FrameLayout>(tag) ?: return
        // v1.5.52: typed child-scan (findViewWithTag matched the slot
        // itself — same tag — and crashed on the unsafe cast).
        val renderer = findRemoteRenderer(slot, tag)
        if (renderer != null) {
            destroyRenderer(renderer)
            slot.removeView(renderer)
        }
        if (slot.findViewWithTag<View>("cam-placeholder") == null) {
            slot.addView(makeTilePlaceholder(name ?: remoteNames[identity]),
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))
        }
    }

    /**
     * v1.5.49: restore live video in a remote tile after it was muted —
     * the old renderer was unregistered + released on mute, so attach a
     * fresh one.
     */
    private fun onRemoteCameraOn(ev: RoomEvent.TrackUnmuted) {
        val identity = ev.participant.identity?.value ?: return
        val track = (ev.publication?.track as? RemoteVideoTrack) ?: return
        val tag = remoteSlotTag(identity)
        val slot = grid.findViewWithTag<FrameLayout>(tag) ?: return
        slot.findViewWithTag<View>("cam-placeholder")?.let { slot.removeView(it) }
        // v1.5.52: typed child-scan (see findRemoteRenderer).
        if (findRemoteRenderer(slot, tag) == null) {
            val renderer = makeRenderer(mirror = false)
            renderer.tag = tag
            slot.addView(renderer, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            attachRenderer(track, renderer)
        }
    }

    /**
     * v1.5.49: remove the video renderer from a remote tile on
     * unsubscribe while PRESERVING the participant placeholder tile if
     * they remain connected (they muted the whole publication, or the
     * track flipped to unsubscribed).
     */
    private fun onRemoteTrackDropped(identity: String) {
        val tag = remoteSlotTag(identity)
        val slot = grid.findViewWithTag<FrameLayout>(tag) ?: return
        // v1.5.52: typed child-scan (see findRemoteRenderer).
        val renderer = findRemoteRenderer(slot, tag)
        if (renderer != null) {
            destroyRenderer(renderer)
            slot.removeView(renderer)
        }
        if (slot.findViewWithTag<View>("cam-placeholder") == null) {
            slot.addView(makeTilePlaceholder(remoteNames[identity]),
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))
        }
    }

    /**
     * v1.5.48: fully remove a remote tile when the participant
     * disconnects — renderer unregistered + released, tile removed from
     * the grid, no orphaned video surface left behind.
     */
    private fun onRemoteGone(identity: String) {
        val slot = grid.findViewWithTag<FrameLayout>(remoteSlotTag(identity)) ?: return
        releaseRenderers(slot)
        grid.removeView(slot)
        Log.d(TAG, "remote tile removed for $identity")
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
            } catch (e: Exception) {
                // v1.5.49: silent catches hid real failures — surface a
                // concise message and re-sync the icon to the TRUE state.
                Log.w(TAG, "mic toggle failed: ${e.message}")
                runOnUiThread {
                    syncTrackState()
                    Toast.makeText(this@LiveRoomActivity,
                        R.string.live_mic_toggle_failed, Toast.LENGTH_SHORT).show()
                }
            }
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
            } catch (e: Exception) {
                // v1.5.49: camera init failures (device busy after a
                // force-close, permission revoked, hardware error) were
                // swallowed — the user saw nothing and the icon could
                // drift. Keep the icon honest with the real state, show a
                // concise message, leave the meeting usable for retry.
                Log.w(TAG, "camera toggle failed: ${e.message}", e)
                runOnUiThread {
                    syncTrackState()
                    syncLocalPreview()
                    Toast.makeText(this@LiveRoomActivity,
                        R.string.live_cam_toggle_failed, Toast.LENGTH_LONG).show()
                }
            }
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
            // v1.5.51: remember the latest display name per identity for
            // camera-off placeholders.
            if (id != null) remoteNames[id] = nm
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

    // v1.5.50: the backend /leave POST fires at most once per visit —
    // the Red Leave button, Back and an unexpected terminal disconnect
    // all funnel through postLeave().
    private var leavePosted = false

    private fun leave() {
        if (leaving) return
        leaving = true
        // v1.5.50: single final-cleanup path — disconnect now; release()
        // happens exactly once in shutdownRoom() (leave + onDestroy are
        // both entry points; the guard makes duplicates no-ops).
        try { room?.disconnect() } catch (_: Exception) { }
        postLeave()
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 3000)
    }

    /** v1.5.50: POST /meetings/{id}/leave exactly once, guarded. */
    private fun postLeave() {
        if (leavePosted) return
        leavePosted = true
        try {
            transport.postJson("/meetings/$meetingId/leave", "{}") {
                runOnUiThread { if (!destroyed) finish() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "leave POST failed: ${e.message}")
        }
    }

    /**
     * v1.5.50: THE idempotent final SDK cleanup. LiveKit 2.18.2 contract
     * (SDK-verified): disconnect() tears down the session; release() then
     * frees the Room's engine/audio-module/EGL resources and MUST be
     * called exactly once per Room once it is never used again — every
     * join creates a fresh Room, so an unreleased Room leaks the
     * camera/mic modules and the shared EGL context (the "leave and
     * rejoin fails" defect). Ordering: renderers are detached/destroyed
     * FIRST (they reference room.lkObjects.eglBase), then disconnect,
     * then release — all guarded so leave(), finish() and onDestroy()
     * can all funnel here without double-cleanup.
     */
    private fun shutdownRoom() {        if (released) return
        released = true
        destroyed = true
        val r = room
        room = null
        if (r != null) {
            try { r.disconnect() } catch (_: Exception) { }
            try { r.release() } catch (e: Exception) {
                Log.w(TAG, "room release failed: ${e.message}")
            }
        }
        // Renderers may still reference the Room's EGL context — destroy
        // them AFTER the room is down (destroyRenderer unregisters sinks
        // from tracks first, which no longer exist post-release, so the
        // registry-driven destroy is skipped and only release() runs).
        rendererTracks.clear()
        try { scope.cancel() } catch (_: Exception) { }
        Log.i(TAG, "room shut down and released")
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
        // v1.5.50: renderers are destroyed FIRST (they render through the
        // Room's EGL context and hold track registrations), THEN the Room
        // itself goes through the single idempotent shutdownRoom() path
        // (disconnect + release exactly once).
        destroyed = true
        try {
            val root = findViewById<android.view.ViewGroup>(android.R.id.content)
            releaseRenderers(root)
        } catch (_: Exception) { }
        shutdownRoom()
        super.onDestroy()
    }

    /**
     * v1.5.49: THE canonical renderer teardown. Required sequence:
     *   1. unregister the renderer as a video sink from its track
     *      (while the track association is still known — see
     *      rendererTracks);
     *   2. release the renderer (frees the EGL surface; the released
     *      view stops displaying its last frame);
     *   3. detach the renderer reference from its slot (caller).
     * Idempotent: safe to call repeatedly on the same renderer
     * (removeRenderer on an unregistered sink and release() on a
     * released renderer are guarded). Never touches a renderer that
     * belongs to another participant — the registry is keyed by renderer
     * instance, and each renderer is tagged to exactly one slot.
     */
    private fun destroyRenderer(renderer: io.livekit.android.renderer.SurfaceViewRenderer?) {
        renderer ?: return
        val track = rendererTracks.remove(renderer)
        if (track != null) {
            try { track.removeRenderer(renderer) } catch (e: Exception) {
                Log.w(TAG, "removeRenderer failed: ${e.message}")
            }
        }
        try { renderer.release() } catch (e: Exception) {
            Log.w(TAG, "renderer release failed: ${e.message}")
        }
        Log.d(TAG, "renderer destroyed (track=${track != null})")
    }

    /** Attaches a renderer to a track AND registers the association. */
    private fun attachRenderer(
        track: io.livekit.android.room.track.VideoTrack,
        renderer: io.livekit.android.renderer.SurfaceViewRenderer
    ) {
        track.addRenderer(renderer)
        rendererTracks[renderer] = track
        Log.d(TAG, "renderer attached (registered=${rendererTracks.size})")
    }

    /**
     * v1.5.49: recursively destroys every registered renderer under a
     * view group (including views not in our registry — they are only
     * released, which is safe post-disconnect).
     */
    private fun releaseRenderers(group: android.view.ViewGroup?) {
        group ?: return
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is io.livekit.android.renderer.SurfaceViewRenderer) {
                destroyRenderer(child)
            } else if (child is android.view.ViewGroup) {
                releaseRenderers(child)
            }
        }
    }

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)
}
