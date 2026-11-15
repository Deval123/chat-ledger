package dev.chatledger.board;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The message bundles (task 12a, D4). Every literal key a template names exists in English; the French file has no key English lacks and
 * the same placeholders for each; no ASCII apostrophe where MessageFormat would drop it; French punctuation has its no-break space; and
 * the JVM's locale never picks the language. How much of English the French file covers is printed, and enforced only by the startup
 * guard (French stays off until complete). The dynamic families ({@code status.*}, {@code notice.*}, ...) are checked from their enums
 * by {@link NoticeAndMessagesTest}.
 */
class MessageKeysTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");
    /** {@code #{key}} or {@code #{key(...)}} with a literal key; {@code #{${...}}} is dynamic and skipped. */
    private static final Pattern TEMPLATE_KEY = Pattern.compile("#\\{([A-Za-z][A-Za-z0-9_.]*)[(}]");
    private static final Pattern MSG_KEY = Pattern.compile("#messages\\.msg\\('([A-Za-z][A-Za-z0-9_.]*)'\\)");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)[^}]*}");

    static Properties bundle(String resource) throws IOException {
        Properties p = new Properties();
        try (InputStream in = MessageKeysTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    static Set<String> templateKeys(String template) {
        String text = template.replaceAll("(?s)<!--.*?-->", "");
        Set<String> keys = new TreeSet<>();
        for (Pattern p : List.of(TEMPLATE_KEY, MSG_KEY)) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                keys.add(m.group(1));
            }
        }
        return keys;
    }

    static Set<String> placeholders(String value) {
        Set<String> out = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(value);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** What is wrong with a French bundle against the English one: unknown keys, placeholder drift, apostrophes, punctuation, formatting. */
    static List<String> frenchFaults(Properties en, Properties fr) {
        List<String> out = new ArrayList<>();
        for (String key : new TreeSet<>(fr.stringPropertyNames())) {
            String value = fr.getProperty(key);
            if (!en.containsKey(key)) {
                out.add(key + ": not an English key");
                continue;
            }
            if (!placeholders(value).equals(placeholders(en.getProperty(key)))) {
                out.add(key + ": placeholders " + placeholders(value) + ", English has " + placeholders(en.getProperty(key)));
            }
            if (value.indexOf('\'') >= 0) {
                out.add(key + ": an ASCII apostrophe (write \u2019)");
            }
            Matcher punct = Pattern.compile("(.)([:;?!])").matcher(value);
            while (punct.find()) {
                if (punct.group(1).charAt(0) != '\u00a0') {
                    out.add(key + ": no no-break space before \"" + punct.group(2) + "\"");
                    break;
                }
            }
            if (value.startsWith(":") || value.startsWith(";")) {
                out.add(key + ": no no-break space before the leading punctuation");
            }
            if (!placeholders(value).isEmpty()) {
                // Formatted as Spring will, with text arguments (names and amounts arrive formatted). Whether each argument reaches the
                // text is not checked: only an ASCII apostrophe can hide one, and that is refused above.
                try {
                    new MessageFormat(value, Locale.FRENCH).format(sampleArgs(placeholders(value)));
                } catch (IllegalArgumentException e) {
                    out.add(key + ": not a valid message format");
                }
            }
        }
        return out;
    }

    private static Object[] sampleArgs(Set<String> indices) {
        int max = indices.stream().mapToInt(Integer::parseInt).max().orElse(-1);
        Object[] args = new Object[max + 1];
        for (int i = 0; i <= max; i++) {
            args[i] = "ARG" + i;
        }
        return args;
    }

    @Test
    void every_key_a_template_names_exists_in_english() throws IOException {
        Properties en = bundle("/messages.properties");
        Set<String> missing = new TreeSet<>();
        int seen = 0;
        try (Stream<Path> walk = Files.walk(TEMPLATES)) {
            for (Path t : walk.filter(p -> p.toString().endsWith(".html")).toList()) {
                for (String key : templateKeys(Files.readString(t))) {
                    seen++;
                    if (!en.containsKey(key)) {
                        missing.add(TEMPLATES.relativize(t) + ": " + key);
                    }
                }
            }
        }
        assertThat(seen).as("the scan found the keys").isGreaterThan(150);
        assertThat(missing).isEmpty();
    }

    @Test
    void no_template_text_is_left_in_english_outside_the_bundle() throws IOException {
        // The sign-in and error pages were the last with fixed English (task 12a); their titles now come from keys too.
        for (String page : List.of("login.html", "error.html")) {
            String t = Files.readString(TEMPLATES.resolve(page));
            assertThat(t).as(page + ": the title from a key").contains("head(#{page.");
            assertThat(t.replaceAll("(?s)<!--.*?-->", "")).as(page + ": every heading, label, button and paragraph from a key")
                    .doesNotContainPattern("<(h1|label|button|p|a)\\b(?![^>]*th:text)[^>]*>[^<]*[A-Za-z]{3}");
        }
    }

    @Test
    void the_french_file_matches_the_english_one_and_formats_safely() throws IOException {
        Properties en = bundle("/messages.properties");
        Properties fr = bundle("/messages_fr.properties");
        assertThat(frenchFaults(en, fr)).isEmpty();
        Set<String> untranslated = new TreeSet<>(en.stringPropertyNames());
        untranslated.removeAll(fr.stringPropertyNames());
        System.out.printf("messages_fr.properties: %d of %d keys translated, %d not yet%n", fr.size(), en.size(), untranslated.size());
        assertThat(fr.size()).as("the demo path is translated").isGreaterThan(200);
    }

    /** The English keys whose message takes arguments and has an ASCII apostrophe, which MessageFormat would drop. */
    static List<String> englishApostrophes(Properties en) {
        List<String> bad = new ArrayList<>();
        for (String key : new TreeSet<>(en.stringPropertyNames())) {
            String value = en.getProperty(key);
            if (!placeholders(value).isEmpty() && value.indexOf('\'') >= 0) {
                bad.add(key);
            }
        }
        return bad;
    }

    @Test
    void no_english_message_with_arguments_has_an_ascii_apostrophe() throws IOException {
        assertThat(englishApostrophes(bundle("/messages.properties"))).as("MessageFormat would drop these apostrophes").isEmpty();
        Properties control = new Properties();
        control.setProperty("a", "The seller's total is {0}");
        control.setProperty("b", "The seller's total");
        assertThat(englishApostrophes(control)).as("only a message with arguments").containsExactly("a");
    }

    @Test
    void each_invented_fault_in_a_french_file_is_caught() throws IOException {
        Properties en = bundle("/messages.properties");
        String[][] faults = {
                {"no.such.key", "Bonjour"},
                {"summary.title", "Résumé"},
                {"summary.title", "Résumé du {0} {1}"},
                {"board.limit", "Cette page n'en montre pas davantage."},
                {"summary.collected", "Encaissé ce jour-là : {0}"},
                {"summary.collected", "Encaissé ce jour-là: {0}"},
                {"demo.chat.reply", "Merci, c’est noté !"},
                {"card.for", ": {0}, {1}"},
                {"summary.title", "Résumé du '{0}'"},
                {"summary.title", "Résumé du {0"},
                {"summary.title", "Résumé du {0,number}"}};
        for (String[] fault : faults) {
            Properties fr = new Properties();
            fr.setProperty(fault[0], fault[1]);
            assertThat(frenchFaults(en, fr)).as(fault[0] + "=" + fault[1]).isNotEmpty();
        }
        Properties good = new Properties();
        good.setProperty("summary.collected", "Encaissé ce jour-là\u00a0: {0}");
        good.setProperty("card.for", "\u00a0: {0}, {1}");
        good.setProperty("flag.line", "{0} ({1})");
        assertThat(frenchFaults(en, good)).isEmpty();
        assertThat(templateKeys("<p th:text=\"#{a.b}\"></p><p th:text=\"#{c.d(${x})}\"></p><p th:text=\"#{${'e.' + f}}\"></p>"
                + "<!-- #{in.comment} --><p th:text=\"${#messages.msg('g.h')}\"></p>")).containsExactly("a.b", "c.d", "g.h");
    }

    /**
     * Spring asks Java for the bundle of the page's locale; with the system-locale fallback on, Java falls back to the JVM's locale when
     * only the base file matches, so a French machine would serve French on English pages. The application turns it off
     * ({@code spring.messages.fallback-to-system-locale=false}, held by StartupGuards); this shows both sides on a French JVM.
     */
    @Test
    void the_jvm_locale_never_chooses_the_language() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.FRANCE);
            assertThat(source(true).getMessage("board.title", null, Locale.ENGLISH)).as("the hazard, with the fallback on").isEqualTo("Tableau");
            assertThat(source(false).getMessage("board.title", null, Locale.ENGLISH)).as("the application's setting").isEqualTo("Board");
            assertThat(source(false).getMessage("login.title", null, Locale.FRENCH)).as("an untranslated key falls back to English")
                    .isEqualTo("Sign in");
        } finally {
            Locale.setDefault(before);
        }
    }

    private static ResourceBundleMessageSource source(boolean fallbackToSystemLocale) {
        ResourceBundleMessageSource s = new ResourceBundleMessageSource();
        s.setBasename("messages");
        s.setDefaultEncoding("UTF-8");
        s.setFallbackToSystemLocale(fallbackToSystemLocale);
        return s;
    }
}
