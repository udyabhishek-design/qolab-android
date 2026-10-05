package dev.drytis.qolab;

/**
 * Pure room-theme styling rules for the Home Social Rooms and Department
 * cards. Extracted from HomeActivity so the palette mapping is unit-testable
 * on the JVM (no Android imports).
 *
 * The approved reference assigns fixed identity colors per room theme:
 *   lobby  → cobalt card, white title, cream text
 *   music  → purple card, lime title accent, lime enter button
 *   coffee → deep forest green card, lime accents
 *   any other/fallback → cobalt (lobby treatment)
 */
public final class RoomThemeStyle {

    public static final int LOBBY_BG      = 0xFF2148C0;
    public static final int LOBBY_TITLE   = 0xFFFFFFFF;
    public static final int LOBBY_TEXT    = 0xFFF5F0E4;
    public static final int LOBBY_ENTER_BG  = 0xFFF5F0E4;
    public static final int LOBBY_ENTER_TXT = 0xFF2148C0;

    public static final int MUSIC_BG      = 0xFF6B21A8;
    public static final int MUSIC_TITLE   = 0xFFF4FF6B;
    public static final int MUSIC_TEXT    = 0xFFFFFFFF;
    public static final int MUSIC_ENTER_BG  = 0xFFF4FF6B;
    public static final int MUSIC_ENTER_TXT = 0xFF6B21A8;

    public static final int COFFEE_BG     = 0xFF14532D;
    public static final int COFFEE_TITLE  = 0xFFF4FF6B;
    public static final int COFFEE_TEXT   = 0xFFF4FF6B;
    public static final int COFFEE_ENTER_BG  = 0xFFF4FF6B;
    public static final int COFFEE_ENTER_TXT = 0xFF14532D;

    /** Immutable resolved style for one room card. */
    public static final class Style {
        public final int bg;
        public final int title;
        public final int text;
        public final int enterBg;
        public final int enterText;
        /** Which default description string to use when the payload has none. */
        public final String descriptionKey;

        Style(int bg, int title, int text, int enterBg, int enterText, String descriptionKey) {
            this.bg = bg;
            this.title = title;
            this.text = text;
            this.enterBg = enterBg;
            this.enterText = enterText;
            this.descriptionKey = descriptionKey;
        }
    }

    /**
     * Preferred visual order for the known room identities on Home:
     * Lobby (0) → Music Lounge (1) → Coffee Lounge (2). Unknown rooms sort
     * last, keeping their payload order (stable when used with a stable sort).
     */
    public static int preferredOrder(String roomTheme, String roomSlug) {
        String theme = roomTheme == null ? "" : roomTheme;
        String slug = roomSlug == null ? "" : roomSlug;
        if (theme.contains("lobby") || slug.contains("lobby")) return 0;
        if (theme.contains("music") || slug.contains("music")) return 1;
        if (theme.contains("coffee") || slug.contains("coffee")) return 2;
        return 3;
    }

    private RoomThemeStyle() {
    }

    /** Resolve the card style from the payload's room_theme / room_slug. */
    public static Style styleFor(String roomTheme, String roomSlug) {
        String theme = roomTheme == null ? "" : roomTheme;
        String slug = roomSlug == null ? "" : roomSlug;
        if (theme.contains("music") || slug.contains("music")) {
            return new Style(MUSIC_BG, MUSIC_TITLE, MUSIC_TEXT,
                    MUSIC_ENTER_BG, MUSIC_ENTER_TXT, "home_room_desc_music");
        }
        if (theme.contains("coffee") || slug.contains("coffee")) {
            return new Style(COFFEE_BG, COFFEE_TITLE, COFFEE_TEXT,
                    COFFEE_ENTER_BG, COFFEE_ENTER_TXT, "home_room_desc_coffee");
        }
        if (theme.contains("lobby") || slug.contains("lobby")) {
            return new Style(LOBBY_BG, LOBBY_TITLE, LOBBY_TEXT,
                    LOBBY_ENTER_BG, LOBBY_ENTER_TXT, "home_room_desc_lobby");
        }
        // Fallback: cobalt/lobby treatment — never crashes on unknown themes.
        return new Style(LOBBY_BG, LOBBY_TITLE, LOBBY_TEXT,
                LOBBY_ENTER_BG, LOBBY_ENTER_TXT, "home_room_default_description");
    }
}
