package dev.chatledger.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The content security policy allows no inline code, so no template may contain any, and no template
 * may switch off Thymeleaf's output escaping. This reads every template. Each rule is first shown to
 * catch an invented template, so a clean result means something.
 *
 * <p>Requirement: 9.1 (encode all output, strict CSP). Task 3.8.
 */
class TemplateLintTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    private static final Map RULES = new Map();

    /** Name and pattern of each forbidden construct, applied to the template with HTML comments removed. */
    private static final class Map {
        final List<String> names = new ArrayList<>();
        final List<Pattern> patterns = new ArrayList<>();

        Map add(String name, String regex) {
            names.add(name);
            patterns.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
            return this;
        }
    }

    static {
        RULES.add("inline <script> (only <script src=...> is allowed)", "<script\\b(?![^>]*\\bsrc\\s*=)")
                .add("<style> element", "<style\\b")
                .add("style attribute", "[\\s\"']style\\s*=")
                .add("th:style / th:styleappend", "\\bth:style")
                .add("inline event handler (on...=)", "\\son[a-z]+\\s*=")
                .add("th:on... handler", "\\bth:on[a-z]+")
                .add("th:utext (unescaped output)", "\\bth:utext\\b")
                .add("unescaped inline [(...)]", "\\[\\(")
                .add("th:inline javascript or css", "th:inline\\s*=\\s*[\"']?(javascript|css)")
                .add("javascript: URL", "javascript\\s*:")
                .add("<iframe>, <object>, <embed> or <base>", "<(iframe|object|embed|base)\\b")
                .add("data: URL", "=\\s*[\"']\\s*data:")
                .add("th:inline of any kind", "th:inline")
                .add("an http(s) address (everything is served from this origin)", "https?://")
                .add("an hx- attribute built from an expression (hx-* must be literal)", "th:attr\\s*=\\s*[\"'][^\"']*hx-(?!headers)")
                .add("th:hx-... attribute", "\\bth:hx-")
                .add("an hx- attribute value that is an expression", "\\shx-(?!headers)[a-z-]+\\s*=\\s*[\"']\\s*[$@{]")
                // Task 12a: data (a name, a reference) never goes into an attribute. Accessible names carry data as visually hidden text
                // printed by th:text; an aria- attribute may only hold a message (#{...}) or an id built from a uuid.
                .add("an aria- label or description built from data", "aria-(label|description|valuetext|roledescription)\\s*=\\s*[|\"']?[^\"'#]*\\$\\{")
                // Task 12a: a page title is kept in the browser's history and may be synced off the phone; it never names a customer
                // or carries an amount. A column name or a day is allowed.
                .add("a page title built from personal data or money",
                        "(pageTitle\\s*=|::\\s*head\\()[^\"\\n]*(\\.who\\b|@money|@display|phone|last4|customer)");
    }

    static List<String> violations(String name, String template) {
        String text = template.replaceAll("(?s)<!--.*?-->", "").replace("xmlns:th=\"http://www.thymeleaf.org\"", "");
        List<String> found = new ArrayList<>();
        for (int i = 0; i < RULES.patterns.size(); i++) {
            if (RULES.patterns.get(i).matcher(text).find()) {
                found.add(name + ": " + RULES.names.get(i));
            }
        }
        return found;
    }

    @Test
    void no_template_contains_inline_code_or_unescaped_output() throws IOException {
        List<Path> templates = templates();
        assertThat(templates).as("the scan found the templates").hasSizeGreaterThanOrEqualTo(4);
        List<String> found = new ArrayList<>();
        for (Path t : templates) {
            found.addAll(violations(TEMPLATES.relativize(t).toString(), Files.readString(t)));
        }
        assertThat(found).isEmpty();
    }

    @Test
    void every_page_for_signed_in_users_puts_the_csrf_token_on_the_body_for_htmx() throws IOException {
        List<Path> pages = new ArrayList<>();
        for (Path t : templates()) {
            if (TEMPLATES.relativize(t).startsWith("app")) {
                pages.add(t);
            }
        }
        assertThat(pages).isNotEmpty();
        for (Path page : pages) {
            assertThat(Files.readString(page).replaceAll("(?s)<!--.*?-->", ""))
                    .as(page + " has hx-headers with the CSRF token on <body>")
                    .containsPattern("<body[^>]*hx-headers=[^>]*_csrf\\.headerName[^>]*_csrf\\.token");
        }
    }

    @Test
    void the_htmx_configuration_turns_off_what_the_csp_forbids() throws IOException {
        String layout = Files.readString(TEMPLATES.resolve("fragments/layout.html"));
        assertThat(layout).contains("name=\"htmx-config\"").contains("\"allowEval\":false")
                .contains("\"allowScriptTags\":false").contains("\"includeIndicatorStyles\":false")
                .contains("\"selfRequestsOnly\":true").contains("\"historyEnabled\":false")
                .contains("\"responseHandling\"").contains("\"target\":\"#notice\"")
                // the refused answer's text goes inside the live region; the region itself is never replaced (task 12a, WCAG 4.1.3)
                .contains("\"select\":\"#notice > *\",\"swapOverride\":\"innerHTML\"").doesNotContain("\"swapOverride\":\"outerHTML\"");
    }

    /** htmx swaps a refused answer into #notice (see the layout's responseHandling); the error page must have one, or the refusal is lost. */
    @Test
    void the_error_page_has_the_notice_element_that_htmx_swaps_in() throws IOException {
        assertThat(Files.readString(TEMPLATES.resolve("error.html"))).containsPattern("<p id=\"notice\"[^>]*><span>The request could not be completed");
    }

    @Test
    void the_layout_loads_the_vendored_htmx_and_the_one_stylesheet_from_this_origin() throws IOException {
        String layout = Files.readString(TEMPLATES.resolve("fragments/layout.html"));
        assertThat(layout).contains("th:src=\"@{/assets/htmx-2.0.11.min.js}\"").contains("th:href=\"@{/assets/app.css}\"");
        assertThat(Path.of("src/main/resources/static/htmx-2.0.11.min.js")).exists();
        assertThat(Path.of("src/main/resources/static/app.css")).exists();
    }

    @Test
    void the_lint_catches_each_invented_violation() {
        String[] bad = {
                "<script>alert(1)</script>",
                "<SCRIPT type=\"text/javascript\">x</SCRIPT>",
                "<style>p{}</style>",
                "<p style=\"color:red\">x</p>",
                "<p th:style=\"'color:red'\">x</p>",
                "<a onclick=\"x()\">x</a>",
                "<a ONMOUSEOVER = \"x()\">x</a>",
                "<a th:onclick=\"${x}\">x</a>",
                "<p th:utext=\"${x}\"></p>",
                "<p>[(${x})]</p>",
                "<script th:inline=\"javascript\" src=\"/a.js\"></script>",
                "<a href=\"javascript:alert(1)\">x</a>",
                "<iframe src=\"/x\"></iframe>",
                "<base href=\"/\">",
                "<img src=\"data:image/png;base64,AAAA\">",
                "<p th:inline=\"text\">[[${x}]]</p>",
                "<a href=\"http://example.invalid/\">x</a>",
                "<form th:attr=\"hx-post=${x}\"></form>",
                "<form th:hx-post=\"${x}\"></form>",
                "<form hx-post=\"${x}\"></form>",
                "<article th:attr=\"aria-label=${card.who}\"></article>",
                "<button th:attr=\"aria-label=|Confirm ${who}|\">x</button>",
                "<html th:with=\"pageTitle=#{page.order.named(${@display.text(order.who, 40)})}\">",
                "<html th:with=\"pageTitle=#{page.approve.named(${@money.format(totalMinor, tenant.scale, order.currency)})}\">",
                "<head th:replace=\"~{fragments/layout :: head(${order.who})}\"></head>",
                "<head th:replace=\"~{fragments/layout :: head(${payment.customerLast4})}\"></head>"};
        for (String template : bad) {
            assertThat(violations("t.html", template)).as(template).isNotEmpty();
        }
    }

    @Test
    void the_lint_accepts_ordinary_markup_and_ignores_comments() {
        assertThat(violations("t.html", "<script src=\"/js/htmx.min.js\"></script><p th:text=\"${x}\">x</p>")).isEmpty();
        assertThat(violations("t.html", "<form th:action=\"@{/login}\" method=\"post\"><input name=\"a\"></form>")).isEmpty();
        assertThat(violations("t.html", "<!-- th:utext and onclick= and style=\"x\" are mentioned only here --><p>x</p>")).isEmpty();
        assertThat(violations("t.html", "<p>Sign-in style guide: [x]</p>")).isEmpty();
        assertThat(violations("t.html", "<nav th:attr=\"aria-label=#{nav.label}\"></nav><section th:attr=\"aria-labelledby=|h-${id}|\"></section>")).isEmpty();
        assertThat(violations("t.html", "<html th:with=\"pageTitle=#{page.column.named(${#messages.msg('column.' + column.name())})}\">"
                + "<head th:replace=\"~{fragments/layout :: head(#{page.order})}\"></head>")).isEmpty();
    }

    private static List<Path> templates() {
        try (Stream<Path> walk = Files.walk(TEMPLATES)) {
            return walk.filter(p -> p.toString().endsWith(".html")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
