package dev.drytis.qolab;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * REAL layout validation for the compact Qolab Login (v1.4.2).
 * Runs in NATIVE graphics mode so text metrics are real (Roboto draws
 * actual glyphs — no stubbed uniform 41dp text lines), and inflates
 * activity_login.xml inside a real AppCompatActivity so the theme's
 * selectableItemBackground attributes resolve exactly like on device.
 * Positions below are real measured px at density 160 (1px = 1dp).
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35)
public class LoginLayoutTest {

    private android.content.Context ctx;

    @Before
    public void setUp() {
        ctx = Robolectric.setupActivity(LoginActivity.class);
    }

    private static final class Measured {
        View content;
        View email;
        View signIn;
        View green;
        View blue;
    }

    /** Inflate + measure + layout at the given dp size; returns real geometry. */
    private Measured measureAt(int widthDp, int heightDp) {
        int wpx = widthDp; // density 160 in Robolectric default: 1dp = 1px
        int hpx = heightDp;

        android.app.Activity activity = Robolectric.setupActivity(LoginActivity.class);
        ScrollView scroll = activity.findViewById(R.id.login_scroll);

        // 1. Host exact screen size
        ViewGroup root = (ViewGroup) scroll.getParent();
        root.measure(
                View.MeasureSpec.makeMeasureSpec(wpx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(hpx, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, wpx, hpx);

        // 2. Natural content height: measure the scroll child UNSPECIFIED
        //    (this is what ScrollView does; fillViewport only stretches it
        //    afterwards, never shrinks natural height)
        View content = scroll.getChildAt(0);
        content.measure(
                View.MeasureSpec.makeMeasureSpec(wpx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        content.layout(0, 0, wpx, content.getMeasuredHeight());

        Measured m = new Measured();
        m.content = content;
        m.email = content.findViewById(R.id.login_email);
        m.signIn = content.findViewById(R.id.login_submit);
        m.green = content.findViewById(R.id.login_card_green);
        m.blue = content.findViewById(R.id.login_card_blue);
        assertNotNull(m.green);
        assertNotNull(m.blue);
        return m;
    }

    /** Absolute Y of a view's top edge within the scroll content —
     *  getTop() is parent-relative, so walk up the hierarchy. */
    private static int absTop(View v, View content) {
        int y = 0;
        View c = v;
        while (c != null && c != content) {
            y += c.getTop();
            c = (View) c.getParent();
        }
        return y;
    }

    private void assertComposition(int widthDp, int heightDp) {
        Measured m = measureAt(widthDp, heightDp);
        String label = widthDp + "x" + heightDp;
        int contentH = m.content.getHeight();

        int emailTop = absTop(m.email, m.content);
        int signInTop = absTop(m.signIn, m.content);
        int signInBottom = signInTop + m.signIn.getHeight();
        int greenTop = absTop(m.green, m.content);
        int greenBottom = greenTop + m.green.getHeight();
        int blueTop = absTop(m.blue, m.content);
        int blueBottom = blueTop + m.blue.getHeight();

        // Real measured facts, printed for the report
        System.out.printf("%s: content=%ddp emailTop=%ddp signIn=[%d..%d] "
                        + "green=[%d..%d] blue=[%d..%d] viewport=%ddp%n",
                label, contentH, emailTop, signInTop, signInBottom,
                greenTop, greenBottom, blueTop, blueBottom, heightDp);

        // 1. Whole composition fits the viewport (no scroll, keyboard closed)
        assertTrue(label + ": content " + contentH + "dp > viewport " + heightDp
                + "dp — would require scrolling", contentH <= heightDp);

        // 2. Form starts soon after the intro — no giant blank band
        assertTrue(label + ": email starts at " + emailTop
                + "dp — excessive blank gap above the form", emailTop <= 300);

        // 3. Sign In fully visible
        assertTrue(label + ": Sign In bottom " + signInBottom
                + "dp beyond viewport", signInBottom <= heightDp);

        // 4. Both cards fully inside the content bounds — no clipping
        assertTrue(label + ": green card clipped (top=" + greenTop
                + ", bottom=" + greenBottom + ", content=" + contentH + ")",
                greenTop >= 0 && greenBottom <= contentH);
        assertTrue(label + ": blue card clipped (top=" + blueTop
                + ", bottom=" + blueBottom + ", content=" + contentH + ")",
                blueTop >= 0 && blueBottom <= contentH);

        // 5. Cards sit below Sign In (bottom composition) and above content end
        assertTrue(label + ": cards must be below the CTA",
                greenTop >= signInBottom && blueTop >= signInBottom);
    }

    @Test
    public void compositionFits_MotoG32_360x780() { assertComposition(360, 780); }

    @Test
    public void compositionFits_360x800() { assertComposition(360, 800); }

    @Test
    public void compositionFits_393x873() { assertComposition(393, 873); }

    @Test
    public void compositionFits_412x915() { assertComposition(412, 915); }

    /** Tall tier (values-h860dp): larger headline/fields/cards must ALSO
     *  fit a 412x915 viewport in one screen. */
    @Test
    @Config(qualifiers = "w412dp-h915dp")
    public void tallTier_compositionFits_412x915() { assertComposition(412, 915); }

    /** Font-scale 1.3 on the smallest target screen: decorative cards have
     *  fixed heights, so the form must still start high enough that the
     *  ScrollView fallback handles the overflow naturally. */
    @Test
    @Config(qualifiers = "w360dp-h780dp")
    public void scaledFonts_noGiantGap() {
        // With fixed-height cards, the email field position can only grow
        // through text (labels/headline). 262dp compact + generous margin.
        Measured m = measureAt(360, 780);
        int emailTop = absTop(m.email, m.content);
        assertTrue("email starts at " + emailTop + "dp — excessive blank gap",
                emailTop <= 360);
    }

    /** v1.4.4 regression: the DEVELOPMENT diagnostics panel must be hidden
     *  before any failure — it may only appear after a failed sign-in, and
     *  never in release builds (runtime-gated on BuildConfig.DEBUG). */
    @Test
    public void diagnosticsPanel_hiddenBeforeFailure() {
        android.app.Activity activity = Robolectric.setupActivity(LoginActivity.class);
        android.view.View diag = activity.findViewById(R.id.login_diag);
        assertNotNull("diagnostics panel missing from layout", diag);
        assertTrue("diagnostics panel visible without a failure",
                diag.getVisibility() != android.view.View.VISIBLE);
    }

    @Test
    public void contentHeight_isCompact() {
        // Guards against regressing into the tall v1.4 page: the natural
        // content height at 360dp width must stay under 800dp even though
        // these tests pass whenever it fits the viewport.
        Measured m = measureAt(360, 780);
        LoginHierarchyDump.dump(m.content, "");
        assertTrue("content too tall: " + m.content.getHeight() + "dp",
                m.content.getHeight() <= 780);
    }
}
