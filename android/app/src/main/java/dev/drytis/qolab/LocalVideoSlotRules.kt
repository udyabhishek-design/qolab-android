package dev.drytis.qolab

/**
 * v1.5.48 — pure, JVM-testable decision logic for the meeting video slots.
 *
 * CONFIRMED ROOT CAUSE (from the v1.5.47 read-only diagnostic): LiveKit
 * Android 2.18.2 `LocalParticipant.setCameraEnabled(false)` does NOT
 * unpublish the camera track. It (1) marks the existing publication muted,
 * (2) stops capture, and (3) keeps the track attached to the publication.
 * The previous v1.5.42 logic decided display purely from "a track object
 * exists", so camera-OFF left the SurfaceViewRenderer attached to a muted,
 * capture-stopped track — the renderer retained its final frame and the
 * frozen image stayed on screen forever.
 *
 * The decision must therefore require the publication's MUTED state:
 * a slot shows live video only when (a) a publication exists,
 * (b) its track is a LocalVideoTrack, and (c) the publication is NOT
 * muted. All three inputs are read from real LiveKit state via
 * [io.livekit.android.room.track.TrackPublication.getMuted] — never from
 * cached UI flags that can drift.
 */
object LocalVideoSlotRules {

    /** Display state of a local (or remote) camera slot. */
    enum class SlotState { LIVE, PLACEHOLDER }

    /**
     * Decision for the LOCAL camera slot.
     *
     * @param publicationExists a camera track publication exists for the local participant
     * @param trackIsLiveVideo  the publication's track is a LocalVideoTrack
     * @param publicationMuted  the publication's `muted` flag (LiveKit keeps
     *                          muted tracks published — muted != unpublished)
     * @return [SlotState.LIVE] only when the camera is truly publishing
     */
    @JvmStatic
    fun localSlot(
        publicationExists: Boolean,
        trackIsLiveVideo: Boolean,
        publicationMuted: Boolean
    ): SlotState {
        if (!publicationExists) return SlotState.PLACEHOLDER
        if (!trackIsLiveVideo) return SlotState.PLACEHOLDER
        if (publicationMuted) return SlotState.PLACEHOLDER
        return SlotState.LIVE
    }

    /**
     * Decision for a REMOTE camera slot.
     *
     * Same principle as [localSlot]: a muted remote publication must not
     * keep displaying a frozen frame. Additionally, a remote slot whose
     * track is gone (unsubscribed / participant left) is also a
     * placeholder, not live video.
     */
    @JvmStatic
    fun remoteSlot(
        trackExists: Boolean,
        publicationMuted: Boolean
    ): SlotState {
        if (!trackExists) return SlotState.PLACEHOLDER
        if (publicationMuted) return SlotState.PLACEHOLDER
        return SlotState.LIVE
    }
}
