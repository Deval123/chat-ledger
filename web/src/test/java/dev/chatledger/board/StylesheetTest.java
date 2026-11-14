package dev.chatledger.board;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one stylesheet keeps what accessibility needs (task 12a, WCAG 2.2 AA): every colour is a declared one, and every pair of text and
 * background, and of control border or focus ring and background, has enough contrast (4.5:1 for text, 3:1 for controls); a visible focus
 * on everything that can take it, never removed; no motion; and below 60em the columns stack, so nothing scrolls sideways (1.4.10).
 * No browser: the file is read and the ratios computed with the WCAG formula.
 */
class StylesheetTest {

    static final Path CSS = Path.of("src/main/resources/static/app.css");

    /** Every colour the stylesheet may use. A new one fails here until its pairs are declared below. */
    static final Set<String> PALETTE = Set.of("#0b4f9c", "#1a7f37", "#1b1f23", "#4a5560", "#8a1c14", "#8a94a0", "#b3261e", "#d5d9de",
            "#e8f1fb", "#e9f6ec", "#eef0f3", "#f0f2f4", "#f6f7f9", "#fdecea", "#ffffff");

    /** Foreground, background, the minimum ratio, and where the pair is used. */
    record Pair(String fg, String bg, double min, String where) {
    }

    static final List<Pair> PAIRS = List.of(
            new Pair("#1b1f23", "#f6f7f9", 4.5, "body text on the page"),
            new Pair("#1b1f23", "#ffffff", 4.5, "text on cards, the header, details"),
            new Pair("#1b1f23", "#e8f1fb", 4.5, "the notice, the seller's chat bubble"),
            new Pair("#1b1f23", "#e9f6ec", 4.5, "a done card, the books balance"),
            new Pair("#1b1f23", "#fdecea", 4.5, "an alert"),
            new Pair("#1b1f23", "#f0f2f4", 4.5, "the message as received"),
            new Pair("#1b1f23", "#eef0f3", 4.5, "the customer's chat bubble"),
            new Pair("#0b4f9c", "#f6f7f9", 4.5, "links on the page"),
            new Pair("#0b4f9c", "#ffffff", 4.5, "links and secondary buttons on white, the skip link"),
            new Pair("#0b4f9c", "#e8f1fb", 4.5, "a link in the notice"),
            new Pair("#0b4f9c", "#e9f6ec", 4.5, "a link on a done card"),
            new Pair("#ffffff", "#0b4f9c", 4.5, "primary button text"),
            new Pair("#4a5560", "#f6f7f9", 4.5, "muted text on the page"),
            new Pair("#4a5560", "#ffffff", 4.5, "muted text on a card"),
            new Pair("#4a5560", "#e9f6ec", 4.5, "muted text on a done card"),
            new Pair("#8a1c14", "#ffffff", 4.5, "flag text on a card"),
            new Pair("#8a94a0", "#ffffff", 3.0, "the border of a text field (1.4.11)"),
            new Pair("#0b4f9c", "#ffffff", 3.0, "the focus ring on white (1.4.11, 2.4.7)"),
            new Pair("#0b4f9c", "#f6f7f9", 3.0, "the focus ring on the page"),
            new Pair("#1a7f37", "#e9f6ec", 3.0, "the edge of a done card"),
            new Pair("#b3261e", "#fdecea", 3.0, "the edge of an alert"));

    static String css() throws IOException {
        return Files.readString(CSS).replaceAll("(?s)/\\*.*?\\*/", "");
    }

    static String normal(String hex) {
        String h = hex.toLowerCase(Locale.ROOT);
        return h.length() == 4 ? "#" + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2) + h.charAt(3) + h.charAt(3) : h;
    }

    static double luminance(String hex) {
        String h = normal(hex);
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = Integer.parseInt(h.substring(1 + 2 * i, 3 + 2 * i), 16) / 255.0;
            c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    static double ratio(String a, String b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** The colours the stylesheet uses that are not in the palette. */
    static Set<String> undeclared(String css) {
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("#[0-9a-fA-F]{3}(?:[0-9a-fA-F]{3})?\\b").matcher(css);
        while (m.find()) {
            if (!PALETTE.contains(normal(m.group()))) {
                out.add(normal(m.group()));
            }
        }
        if (Pattern.compile("(?i)\\b(rgb|rgba|hsl|hsla)\\(|:\\s*(red|white|black|blue|green|gray|grey)\\b").matcher(css).find()) {
            out.add("a colour that is not written as #rrggbb");
        }
        return out;
    }

    /** What the stylesheet does that accessibility forbids. */
    static List<String> faults(String css) {
        List<String> out = new ArrayList<>();
        if (Pattern.compile("outline\\s*:\\s*(none|0)\\b").matcher(css).find()) {
            out.add("an outline is removed");
        }
        if (Pattern.compile("\\b(transition|animation)\\s*:|@keyframes").matcher(css).find()) {
            out.add("motion");
        }
        Matcher focus = Pattern.compile("([^{}]*:focus-visible[^{}]*)\\{([^}]*)}").matcher(css);
        Set<String> focusable = new TreeSet<>();
        boolean ring = false;
        while (focus.find()) {
            for (String sel : focus.group(1).split(",")) {
                focusable.add(sel.trim().replace(":focus-visible", ""));
            }
            ring |= focus.group(2).matches("(?s).*outline\\s*:\\s*3px solid #0b4f9c.*");
        }
        for (String required : List.of("a", "button", "input", "textarea", "select", "summary")) {
            if (!focusable.contains(required)) {
                out.add("no visible focus for " + required);
            }
        }
        if (!ring) {
            out.add("no 3px focus ring");
        }
        // Phone first: the columns are a block below 60em (no sideways scroll), side by side from 60em.
        String outside = css.replaceAll("(?s)@media[^{]*\\{(?:[^{}]*\\{[^}]*})*\\s*}", "");
        if (!Pattern.compile("\\.columns\\s*\\{[^}]*display:\\s*block").matcher(outside).find()) {
            out.add("the columns do not stack below 60em");
        }
        if (Pattern.compile("\\.columns\\s*\\{[^}]*overflow-x:\\s*auto").matcher(css).find()) {
            out.add("the columns scroll sideways");
        }
        if (!Pattern.compile("(?s)@media \\(min-width: 60em\\)\\s*\\{.*\\.columns\\s*\\{[^}]*display:\\s*flex").matcher(css).find()) {
            out.add("the columns are not side by side from 60em");
        }
        if (!Pattern.compile("scroll-padding-top\\s*:\\s*[1-9]").matcher(outside).find()) {
            out.add("nothing keeps a focused element below the sticky chips (2.4.11)");
        }
        // The live region is never hidden: when empty it takes no room, but it stays in the page.
        if (Pattern.compile("\\.notice[^{]*\\{[^}]*display\\s*:\\s*none").matcher(css).find()) {
            out.add("the notice region is hidden");
        }
        if (!css.contains(".notice:empty")) {
            out.add("the empty notice still takes room");
        }
        if (!Pattern.compile("\\.skip:focus[^{]*\\{[^}]*top:\\s*0\\.5rem").matcher(css).find()) {
            out.add("the skip link does not come into view on focus");
        }
        return out;
    }

    @Test
    void every_colour_is_declared_and_every_declared_pair_has_enough_contrast() throws IOException {
        assertThat(undeclared(css())).isEmpty();
        List<String> weak = new ArrayList<>();
        for (Pair p : PAIRS) {
            assertThat(PALETTE).as(p.where()).contains(p.fg(), p.bg());
            double r = ratio(p.fg(), p.bg());
            if (r < p.min()) {
                weak.add(p.where() + ": " + String.format(Locale.ROOT, "%.2f", r) + " < " + p.min());
            }
        }
        assertThat(weak).isEmpty();
    }

    @Test
    void the_stylesheet_keeps_focus_motion_reflow_and_the_live_region_right() throws IOException {
        assertThat(faults(css())).isEmpty();
    }

    @Test
    void the_formula_is_the_wcag_one() {
        assertThat(ratio("#000000", "#ffffff")).isCloseTo(21.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(ratio("#ffffff", "#ffffff")).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(ratio("#767676", "#ffffff")).isCloseTo(4.54, org.assertj.core.data.Offset.offset(0.01));
        assertThat(ratio("#fff", "#0b4f9c")).isEqualTo(ratio("#ffffff", "#0b4f9c"));
    }

    @Test
    void each_invented_fault_is_caught() throws IOException {
        String good = css();
        assertThat(undeclared(good.replace("#4a5560", "#777777"))).contains("#777777");
        assertThat(undeclared(good + " .x { color: rgb(0,0,0); }")).isNotEmpty();
        String[][] bad = {
                {"outline: 3px solid #0b4f9c;", "outline: none;"},
                {"summary:focus-visible, ", ""},
                {"select:focus-visible, ", ""},
                {".columns { display: block; }", ".columns { display: flex; overflow-x: auto; }"},
                {"  .columns { display: flex; gap: 0.75rem; }", "  .columns { gap: 0.75rem; }"},
                {"html { scroll-padding-top: 4.5rem; }", ""},
                {".notice:empty { padding: 0; border: 0; }", ".notice.empty { display: none; }"},
                {".skip:focus, .skip:focus-visible { top: 0.5rem; }", ""}};
        assertThat(faults(good + " .card { transition: color 1s; }")).contains("motion");
        assertThat(faults(good + " @keyframes spin { to { opacity: 0; } }")).contains("motion");
        for (String[] fault : bad) {
            assertThat(good).as("the control text exists: " + fault[0]).contains(fault[0]);
            assertThat(faults(good.replace(fault[0], fault[1]))).as(fault[0]).isNotEmpty();
        }
    }
}
