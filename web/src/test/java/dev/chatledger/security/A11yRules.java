package dev.chatledger.security;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The structural accessibility rules every rendered page must meet (task 12a, WCAG 2.2 AA as far as markup can show it). A rule here
 * never needs a browser: it reads the HTML. Keyboard order, focus after an htmx swap, reflow and zoom are checked by the browser walk
 * ({@code tools/browser-check/a11y-walk.mjs}). Each rule has a negative control in {@link A11yRulesTest}.
 */
final class A11yRules {

    /** Thymeleaf prints a missing message key as {@code ??key_locale??}. */
    private static final Pattern MISSING_KEY = Pattern.compile("\\?\\?[A-Za-z0-9._-]+_[a-z]{2}(_[A-Z]{2})?\\?\\?");
    private static final String FOCUSABLE = "a[href], button, input:not([type=hidden]), select, textarea, summary, [tabindex]";

    private A11yRules() {
    }

    /** Every rule that fails on the page, each as one sentence naming the page. Empty when the page passes. */
    static List<String> violations(String what, String html) {
        Document doc = Jsoup.parse(html);
        List<String> out = new ArrayList<>();
        java.util.function.Consumer<String> fail = s -> out.add(what + ": " + s);

        // Language and title (3.1.1, 2.4.2).
        if (doc.selectFirst("html[lang]") == null || doc.selectFirst("html").attr("lang").isBlank()) {
            fail.accept("no lang on <html>");
        }
        if (doc.title().isBlank()) {
            fail.accept("no <title>");
        }
        // One main, and a skip link to it that comes first (2.4.1).
        Elements mains = doc.select("main");
        if (mains.size() != 1 || !"main".equals(mains.first().id())) {
            fail.accept("there must be exactly one <main id=\"main\">");
        }
        Element first = doc.body() == null ? null : doc.body().selectFirst(FOCUSABLE);
        if (first == null || !first.is("a.skip[href=#main]")) {
            fail.accept("the first focusable element is not the skip link to #main");
        }
        // One h1, and heading levels that never jump (1.3.1, 2.4.6).
        if (doc.select("h1").size() != 1) {
            fail.accept("there must be exactly one <h1>, found " + doc.select("h1").size());
        }
        int last = 0;
        for (Element h : doc.select("h1, h2, h3, h4, h5, h6")) {
            int level = h.tagName().charAt(1) - '0';
            if (last > 0 && level > last + 1) {
                fail.accept("heading level jumps from h" + last + " to h" + level + " at \"" + h.text() + "\"");
            }
            last = level;
        }
        // Every field has a label (1.3.1, 3.3.2, 4.1.2).
        for (Element field : doc.select("input:not([type=hidden]):not([type=submit]):not([type=button]), select, textarea")) {
            boolean labelled = !field.id().isEmpty() && doc.selectFirst("label[for=" + field.id() + "]") != null
                    || !field.attr("aria-label").isBlank() || !field.attr("aria-labelledby").isBlank();
            if (!labelled) {
                fail.accept("a field without a label: " + field.cssSelector());
            }
        }
        // Every button and link has a name; no two buttons share one (2.4.4, 2.4.6, 4.1.2).
        Map<String, Integer> buttons = new HashMap<>();
        for (Element control : doc.select("button, a[href]")) {
            String name = name(control);
            if (name.isEmpty()) {
                fail.accept("a control without a name: " + control.cssSelector());
            }
            if (control.is("button, a.button")) {
                buttons.merge(name, 1, Integer::sum);
            }
        }
        buttons.forEach((name, n) -> {
            if (n > 1) {
                fail.accept(n + " buttons share the name \"" + name + "\"");
            }
        });
        // Links that share a name go to the same place: a screen reader's list of links shows names only (2.4.4, 2.4.9).
        Map<String, String> links = new HashMap<>();
        for (Element link : doc.select("a[href]:not(.button)")) {
            String name = name(link);
            String before = links.putIfAbsent(name, link.attr("href"));
            if (before != null && !before.equals(link.attr("href"))) {
                fail.accept("links named \"" + name + "\" go to " + before + " and " + link.attr("href"));
            }
        }
        // The one live region (4.1.3): on every page with the top bar, exactly one role=status, #notice, never hidden.
        if (doc.selectFirst("header.top") != null) {
            Elements status = doc.select("[role=status]");
            Element notice = doc.getElementById("notice");
            if (status.size() != 1 || notice == null || !status.first().equals(notice)) {
                fail.accept("the page must have exactly one role=status region, and it must be #notice (found " + status.size() + ")");
            } else if (notice.hasAttr("hidden") || notice.hasClass("empty") || !"polite".equals(notice.attr("aria-live"))) {
                fail.accept("#notice must be polite and never hidden");
            }
        }
        // No positive tabindex (2.4.3); every reference points to an element (1.3.1).
        for (Element e : doc.select("[tabindex]")) {
            if (e.attr("tabindex").matches("[1-9][0-9]*")) {
                fail.accept("a positive tabindex: " + e.cssSelector());
            }
        }
        for (Element e : doc.select("[aria-labelledby], [aria-describedby]")) {
            for (String attr : new String[]{"aria-labelledby", "aria-describedby"}) {
                for (String id : e.attr(attr).trim().split("\\s+")) {
                    if (!id.isEmpty() && doc.getElementById(id) == null) {
                        fail.accept(attr + " points to a missing id \"" + id + "\"");
                    }
                }
            }
        }
        // More than one navigation: each has a name (1.3.1, 2.4.1).
        Elements navs = doc.select("nav");
        if (navs.size() > 1) {
            for (Element nav : navs) {
                if (nav.attr("aria-label").isBlank() && nav.attr("aria-labelledby").isBlank()) {
                    fail.accept("a <nav> without a name among several");
                }
            }
        }
        // Images have a text alternative; table headers say what they head (1.1.1, 1.3.1).
        for (Element img : doc.select("img:not([alt])")) {
            fail.accept("an image without alt: " + img.cssSelector());
        }
        for (Element th : doc.select("th:not([scope])")) {
            fail.accept("a table header without scope: \"" + th.text() + "\"");
        }
        // A message key that does not exist.
        if (MISSING_KEY.matcher(html).find()) {
            fail.accept("a missing message key (??...??)");
        }
        return out;
    }

    /** The accessible name as a screen reader computes it for these simple cases: aria-label, else the text, hidden text included. */
    static String name(Element control) {
        String label = control.attr("aria-label");
        return (label.isBlank() ? control.text() : label).replaceAll("\\s+", " ").trim();
    }
}
