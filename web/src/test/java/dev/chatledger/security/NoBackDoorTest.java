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
 * Scans of the repository for what must not be there: a way to create users or tenants other than
 * the operator provisioning functions, a public sign-up route, a password written into a file. Each
 * scanner is first shown to catch an invented violation, so a clean result means something.
 *
 * <p>Requirements: 9.1, 9.4. Tasks 3.4, 3.6.
 */
class NoBackDoorTest {

    private static final Path WEB = Path.of(".");
    private static final Path ROOT = Path.of("..");

    private static final Pattern CREATES_IDENTITY = Pattern.compile(
            "(?is)\\binsert\\s+into\\s+(public\\.)?(tenant|app_user)\\b");
    private static final Pattern SIGNUP_ROUTE = Pattern.compile(
            "(?i)[\"'/](register|signup|sign-up|sign_up|join|create-account|forgot|reset-password)\\b");
    /** name containing password/secret/key/token, then = or :, then a value that is not a reference or placeholder. */
    private static final Pattern LITERAL_SECRET = Pattern.compile(
            "(?i)\\b[\\w.\\-]*(password|passwd|secret|api[_-]?key|token)[\\w.\\-]*\\s*[=:](?![?+\\-])\\s*(?![\\s$<\"']*[$<])(?=\\S)(?!\\$\\{)(?!<)(?!\\s*$)[^\\s]+");

    // ── Only the provisioning functions create tenants and users ─────────────

    @Test
    void migrations_create_tenants_and_users_only_inside_the_provisioning_functions() throws IOException {
        int total = 0;
        List<String> elsewhere = new ArrayList<>();
        try (Stream<Path> files = Files.list(WEB.resolve("src/main/resources/db/migration"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".sql")).toList()) {
                int count = count(CREATES_IDENTITY, strip(Files.readString(file)));
                total += count;
                String name = file.getFileName().toString();
                if (count > 0 && !name.startsWith("V013__") && !name.startsWith("V020__")) {
                    elsewhere.add(file.getFileName() + " has " + count);
                }
            }
        }
        assertThat(elsewhere).isEmpty();
        // V013: provision_tenant (a tenant and a user) and provision_user (a user). V020 (task 9) replaces both (the same three
        // inserts, now refused on a demo deployment) and adds demo_create_tenant (a demo tenant and its seller, refused on a regular one).
        assertThat(total).as("only the provisioning functions and demo_create_tenant insert tenants and users").isEqualTo(8);
    }

    @Test
    void application_code_never_inserts_tenants_or_users() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : files(WEB.resolve("src/main/java"), ".java")) {
            if (CREATES_IDENTITY.matcher(strip(Files.readString(file))).find()) {
                found.add(file.toString());
            }
        }
        assertThat(found).isEmpty();
    }

    @Test
    void the_identity_scanner_catches_an_invented_insert() {
        assertThat(CREATES_IDENTITY.matcher("INSERT INTO app_user (id) VALUES (1)").find()).isTrue();
        assertThat(CREATES_IDENTITY.matcher("insert\n  into public.tenant(id)").find()).isTrue();
        assertThat(CREATES_IDENTITY.matcher("INSERT INTO customer(id)").find()).isFalse();
        assertThat(CREATES_IDENTITY.matcher(strip("-- INSERT INTO tenant\nSELECT 1")).find()).isFalse();
    }

    // ── No public sign-up ────────────────────────────────────────────────────

    @Test
    void there_is_no_sign_up_or_password_reset_route_or_page() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : files(WEB.resolve("src/main/java"), ".java")) {
            String source = Files.readString(file);
            if (source.contains("Mapping") && SIGNUP_ROUTE.matcher(source).find()) {
                found.add(file.toString());
            }
        }
        for (Path template : files(WEB.resolve("src/main/resources/templates"), ".html")) {
            if (SIGNUP_ROUTE.matcher(read(template)).find()) {
                found.add(template.toString());
            }
        }
        assertThat(found).isEmpty();
    }

    @Test
    void the_route_scanner_catches_an_invented_route() {
        assertThat(SIGNUP_ROUTE.matcher("@PostMapping(\"/register\")").find()).isTrue();
        assertThat(SIGNUP_ROUTE.matcher("<a href=\"/signup\">").find()).isTrue();
        assertThat(SIGNUP_ROUTE.matcher("@GetMapping(\"/login\")").find()).isFalse();
    }

    // ── No literal secrets in files ──────────────────────────────────────────

    @Test
    void no_password_or_secret_is_written_into_configuration_scripts_sql_or_workflows() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String dir : new String[]{"src/main/resources", "src/test/resources"}) {
            files.addAll(files(WEB.resolve(dir), null));
        }
        files.removeIf(f -> f.toString().endsWith("common-passwords.txt") || f.toString().endsWith(".html")
                // The vendored htmx script is minified third-party code; its SHA-256 is pinned in HtmxProvenanceTest, so it cannot change unseen.
                || f.toString().endsWith("static/htmx-2.0.11.min.js"));
        files.add(ROOT.resolve("docker-compose.yml"));
        files.add(ROOT.resolve(".env.example"));
        files.addAll(files(ROOT.resolve("docker"), null));
        files.addAll(files(ROOT.resolve(".github"), null));
        assertThat(files).as("the scan found files").hasSizeGreaterThan(8);

        List<String> found = new ArrayList<>();
        for (Path file : files) {
            int line = 0;
            for (String text : Files.readAllLines(file)) {
                line++;
                if (!text.strip().startsWith("#") && !text.strip().startsWith("--") && LITERAL_SECRET.matcher(text).find()) {
                    found.add(file + ":" + line + ": " + text.strip().replaceAll("([=:]).*", "$1 ..."));
                }
            }
        }
        assertThat(found).isEmpty();
    }

    @Test
    void the_secret_scanner_catches_an_invented_password() {
        assertThat(LITERAL_SECRET.matcher("spring.datasource.password=hunter2hunter2").find()).isTrue();
        assertThat(LITERAL_SECRET.matcher("APP_SECRET: ci-secret-not-for-production").find()).isTrue();
        assertThat(LITERAL_SECRET.matcher("  DB_PASSWORD: ci_password").find()).isTrue();
        assertThat(LITERAL_SECRET.matcher("CREATE ROLE x PASSWORD='abc'").find()).isTrue();
        assertThat(LITERAL_SECRET.matcher(": \"${MIGRATE_PASSWORD:?MIGRATE_PASSWORD must be set}\"").find()).isFalse();
        assertThat(LITERAL_SECRET.matcher("spring.datasource.password=${DB_PASSWORD}").find()).isFalse();
        assertThat(LITERAL_SECRET.matcher("APP_SECRET=<replace-with-random-hex-secret>").find()).isFalse();
        assertThat(LITERAL_SECRET.matcher("APP_SECRET=").find()).isFalse();
        assertThat(LITERAL_SECRET.matcher("server.servlet.session.cookie.name=__Host-x").find()).isFalse();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static int count(Pattern pattern, String text) {
        int n = 0;
        var m = pattern.matcher(text);
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** Removes SQL line comments and Java comments so a mention in a comment is not a violation. */
    private static String strip(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)--.*$", "").replaceAll("(?m)//.*$", "");
    }

    private static List<Path> files(Path dir, String suffix) {
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).filter(p -> suffix == null || p.toString().endsWith(suffix)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
