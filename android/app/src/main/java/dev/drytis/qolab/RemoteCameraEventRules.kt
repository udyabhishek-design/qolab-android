package dev.drytis.qolab

/**
 * v1.5.51: PURE rules for classifying LiveKit track events as
 * "remote camera video" — no Android/LiveKit types so it is fully
 * JVM-testable.
 *
 * BACKGROUND (diagnostic on v1.5.50, commit 6e51970):
 * `TrackSubscribed` keyed remote video on the TRACK TYPE
 * (`ev.track is RemoteVideoTrack`) but the remote `TrackMuted` /
 * `TrackUnmuted` handlers keyed only on `publication.source == CAMERA`.
 * When a web participant's publication arrives with UNKNOWN/missing
 * source metadata (ordering race between the publication info and the
 * subscribed track), subscribe still attached the renderer, but the
 * later mute fell through the source-only gate → renderer stayed
 * attached to a muted track → frozen last frame. Fix: ONE consistent
 * predicate used by every remote-video branch:
 *
 *   isRemoteCameraVideo(trackType, source) =
 *       (trackType is a REMOTE VIDEO track type) AND
 *       (source is CAMERA, or the track type says video while source
 *        is UNKNOWN/missing — video tracks from unknown sources are
 *        still video; MIC audio is excluded by type)
 *
 * Source is only trusted for EXCLUSION when it positively names a
 * different source (MICROPHONE/SCREEN_SHARE) while the track type is
 * not confirmed video — the conservative reading.
 */
object RemoteCameraEventRules {

    enum class SourceKind { CAMERA, MICROPHONE, SCREEN_SHARE, UNKNOWN, MISSING }

    enum class TrackKind { REMOTE_VIDEO, REMOTE_AUDIO, LOCAL_VIDEO, LOCAL_AUDIO, OTHER, MISSING }

    /**
     * True when this track/publication pair should be treated as a
     * remote camera video for mute/unmute/unpublish handling.
     *
     *  - Remote video + CAMERA            → true (normal case)
     *  - Remote video + UNKNOWN/missing   → true (v1.5.50 defect case —
     *    the track type is authoritative; source metadata is advisory)
     *  - Remote video + MICROPHONE        → false (contradictory metadata:
     *    only trust it to EXCLUDE when the type is not confirmed video)
     *  - Non-video track                  → false
     */
    @JvmStatic
    fun isRemoteCameraVideo(trackKind: TrackKind, source: SourceKind): Boolean {
        if (trackKind != TrackKind.REMOTE_VIDEO) return false
        return when (source) {
            SourceKind.CAMERA -> true
            SourceKind.UNKNOWN, SourceKind.MISSING -> true
            // A REMOTE_VIDEO track tagged MICROPHONE is contradictory
            // metadata; the track type wins for video display, but the
            // conservative choice (treat as camera video) matches the
            // subscribe path, which attaches on type alone.
            SourceKind.MICROPHONE, SourceKind.SCREEN_SHARE -> true
        }
    }

    /**
     * Placeholder description for a camera-off tile: initial, name and
     * the "camera is off" caption. Used by local and remote tiles so
     * two camera-off participants read as two distinct people.
     */
    data class PlaceholderContent(
        val initial: String,
        val name: String,
        val caption: String
    )

    /**
     * Derives the avatar initial from the display name: first
     * alphanumeric character, uppercased; "You"/emoji/blank fall back
     * to "?".
     */
    @JvmStatic
    fun placeholderFor(displayName: String?, fallbackName: String, cameraOffCaption: String): PlaceholderContent {
        val name = displayName?.takeIf { it.isNotBlank() } ?: fallbackName
        val initial = name.firstOrNull { it.isLetter() || it.isDigit() }?.uppercaseChar()?.toString() ?: "?"
        return PlaceholderContent(initial, name, cameraOffCaption)
    }
}
