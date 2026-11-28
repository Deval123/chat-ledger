package dev.chatledger.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No secret-like value in any file git tracks (register 183). {@link NoBackDoorTest} checks the configuration, scripts, SQL and workflows
 * it names; this test checks every tracked file, so a value in {@code deploy/}, {@code tools/}, a document or a test fails the build too.
 *
 * <p>What it looks for, in every tracked text file: the formats of real tokens (AWS, GitHub, Slack, Docker, npm), private key blocks,
 * JSON web tokens and base64 JSON (the shape of a Cloudflare tunnel token), a password in a URL, and a Docker {@code "auth"} entry. In
 * configuration files (YAML, properties, shell, env, SQL, JSON, TOML, INI, conf, Dockerfile, templates named {@code *.example}): a
 * secret-like name assigned a literal value, with {@link NoBackDoorTest#LITERAL_SECRET}, the same detector, where a file under
 * {@code /run/secrets/} is a reference, not a value. And a tracked file named like an {@code .env} file or a private key.
 *
 * <p>A finding is excused only by an entry of {@link #EXCEPTIONS}: the file, the rule and the SHA-256 of the matched text, so the entry
 * covers that one value in that one file and nothing else. An entry that excuses nothing fails the test, so the list stays exact. The
 * report gives the file, the line and the rule, never the value.
 *
 * <p>What it does not see: a value in prose with no assignment (a document that quotes one), a value in a Java string, and anything
 * outside the files git tracks. Requirement 9. Steering: secrets never in the repository, logs or test fixtures.
 */
class TrackedFilesSecretScanTest {

    private static final Path ROOT = Path.of("..");

    /** An explicit exception: this rule, in this file, for the value whose SHA-256 starts with these 16 hex digits. */
    record Allowed(String path, String rule, String sha256, String why) {
    }

    /** The made-up tunnel token the stack tests write: base64 of JSON whose every value is "made-up". */
    private static final String MADE_UP_TUNNEL_TOKEN = "8ea01546309bd090";

    static final List<Allowed> EXCEPTIONS = List.of(
            new Allowed("deploy/test-stack.sh", "base64-json", MADE_UP_TUNNEL_TOKEN, "the made-up tunnel token of the stack drill"),
            new Allowed("deploy/test-upgrade.sh", "base64-json", MADE_UP_TUNNEL_TOKEN, "the made-up tunnel token of the upgrade rehearsal"),
            new Allowed("web/src/test/java/dev/chatledger/deploy/MakeSecretsScriptIT.java", "base64-json", MADE_UP_TUNNEL_TOKEN,
                    "the made-up tunnel token that make-secrets.sh is tested with"));

    /** Rules for every tracked text file: the name, and the pattern whose whole match is the value. */
    static final Map<String, Pattern> EVERYWHERE = new LinkedHashMap<>();

    static {
        EVERYWHERE.put("aws-access-key-id", Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"));
        EVERYWHERE.put("github-token", Pattern.compile("\\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{36,}|\\bgithub_pat_[A-Za-z0-9_]{50,}"));
        EVERYWHERE.put("slack-token", Pattern.compile("\\bxox[abposr]-[A-Za-z0-9-]{10,}|hooks\\.slack\\.com/services/T[A-Z0-9]+/B[A-Z0-9]+/[A-Za-z0-9]+"));
        EVERYWHERE.put("docker-token", Pattern.compile("\\bdckr_pat_[A-Za-z0-9_-]{20,}"));
        EVERYWHERE.put("npm-token", Pattern.compile("\\bnpm_[A-Za-z0-9]{36}\\b"));
        EVERYWHERE.put("private-key", Pattern.compile("-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY"));
        EVERYWHERE.put("jwt", Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"));
        EVERYWHERE.put("base64-json", Pattern.compile("\\beyJ[A-Za-z0-9+/=_-]{40,}"));
        // a password in a URL, unless it is a reference or a placeholder (${...}, $NAME, <...>, ***)
        EVERYWHERE.put("url-credentials", Pattern.compile("\\b[a-z][a-z0-9+.-]*://[^\\s/:@'\"<>`]+:(?![$<*])[^\\s@/'\"<>`]+@"));
        EVERYWHERE.put("docker-auth", Pattern.compile("\"auth\"\\s*:\\s*\"[A-Za-z0-9+/=]{12,}\""));
    }

    private static final Pattern CONFIG_FILE = Pattern.compile(
            "(?i)(^|/)(Dockerfile(\\.[^/]+)?|[^/]*\\.(ya?ml|properties|sh|env|sql|json|toml|ini|conf|example))$");
    /** A file under /run/secrets/ is where a secret is read from, not a secret. */
    private static final Pattern SECRET_FILE_REFERENCE = Pattern.compile("[=:]\\s*[\"']?/run/secrets/");
    private static final Pattern SECRET_FILE_NAME = Pattern.compile(
            "(?i)(^|/)(\\.env(\\.[^/]+)?|[^/]+\\.(pem|key|p12|pfx|jks|keystore|ppk)|id_(rsa|dsa|ecdsa|ed25519)[^/]*|\\.npmrc|\\.netrc|\\.pgpass|\\.git-credentials)$");
    /** The one tracked file named like a secret: the template, with placeholders only. */
    private static final Set<String> TEMPLATE_NAMES = Set.of(".env.example");

    /** One finding: where and which rule, and the hash of the value (the value itself is never kept). */
    record Finding(String path, int line, String rule, String sha256) {
        @Override
        public String toString() {
            return path + ":" + line + " " + rule;
        }
    }

    /** The findings in one file's text. */
    static List<Finding> findings(String path, String text) {
        List<Finding> out = new ArrayList<>();
        if (SECRET_FILE_NAME.matcher(path).find() && !TEMPLATE_NAMES.contains(path)) {
            out.add(new Finding(path, 0, "secret-file-name", sha256(path)));
        }
        boolean config = CONFIG_FILE.matcher(path).find();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            for (Map.Entry<String, Pattern> rule : EVERYWHERE.entrySet()) {
                Matcher m = rule.getValue().matcher(line);
                while (m.find()) {
                    out.add(new Finding(path, i + 1, rule.getKey(), sha256(m.group())));
                }
            }
            String stripped = line.strip();
            if (config && !stripped.startsWith("#") && !stripped.startsWith("--") && !SECRET_FILE_REFERENCE.matcher(line).find()) {
                Matcher m = NoBackDoorTest.LITERAL_SECRET.matcher(line);
                if (m.find()) {
                    out.add(new Finding(path, i + 1, "secret-assignment", sha256(m.group())));
                }
            }
        }
        return out;
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean excused(Finding f, Allowed e) {
        return e.path().equals(f.path()) && e.rule().equals(f.rule()) && e.sha256().equals(f.sha256());
    }

    /** Every file git tracks, relative to the repository root. */
    private static List<String> tracked() throws IOException, InterruptedException {
        Process git = new ProcessBuilder("git", "ls-files", "-z").directory(ROOT.toFile()).redirectErrorStream(false).start();
        String out = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(git.waitFor()).as("git ls-files ran in the repository root").isZero();
        return List.of(out.split("\0")).stream().filter(s -> !s.isEmpty()).toList();
    }

    @Test
    void no_tracked_file_holds_a_secret_like_value_except_the_listed_examples() throws IOException, InterruptedException {
        List<String> files = tracked();
        assertThat(files).as("the scan found the repository's files").hasSizeGreaterThan(400);
        List<Finding> found = new ArrayList<>();
        int scanned = 0;
        for (String path : files) {
            Path file = ROOT.resolve(path);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            byte[] bytes = Files.readAllBytes(file);
            boolean binary = false;
            for (int i = 0; i < Math.min(bytes.length, 8000); i++) {
                if (bytes[i] == 0) {
                    binary = true;
                    break;
                }
            }
            if (binary) {
                found.addAll(findings(path, ""));   // the name rule still applies
                continue;
            }
            scanned++;
            found.addAll(findings(path, new String(bytes, StandardCharsets.UTF_8)));
        }
        assertThat(scanned).as("text files scanned").isGreaterThan(400);

        List<Finding> unexcused = found.stream().filter(f -> EXCEPTIONS.stream().noneMatch(e -> excused(f, e))).toList();
        assertThat(unexcused).as("secret-like values in tracked files (file:line rule; the values are not printed)").isEmpty();
        List<Allowed> stale = EXCEPTIONS.stream().filter(e -> found.stream().noneMatch(f -> excused(f, e))).toList();
        assertThat(stale).as("exceptions that no longer excuse anything: remove them").isEmpty();
    }

    /** Each rule catches an invented value; the values are built here at run time, so this file holds none of them. */
    @Test
    void the_scanner_catches_each_kind_of_invented_value() {
        String sixteen = "ABCDEFGHIJKLMNOP";
        String alnum36 = "abcdefghijklmnopqrstuvwxyz0123456789";
        Map<String, String> invented = new LinkedHashMap<>();
        invented.put("aws-access-key-id", "key = AKIA" + sixteen);
        invented.put("github-token", "token ghp_" + alnum36);
        invented.put("slack-token", "xoxb-" + "1234567890-abcdef");
        invented.put("docker-token", "dckr_pat_" + alnum36);
        invented.put("npm-token", "npm_" + alnum36);
        invented.put("private-key", "-----BEGIN " + "OPENSSH PRIVATE KEY-----");
        invented.put("jwt", "eyJ" + "abcdefghij.eyJ" + "abcdefghij." + "abcdefghij");
        invented.put("base64-json", "eyJ" + alnum36 + "abcdefgh");
        invented.put("url-credentials", "postgresql://app:" + "example-not-a-real-password" + "@db:5432/x");
        invented.put("docker-auth", "{\"auth\": \"" + "ZXhhbXBsZTpub3QtcmVhbA==" + "\"}");
        for (Map.Entry<String, String> e : invented.entrySet()) {
            assertThat(findings("docs/x.md", e.getValue())).as(e.getKey()).extracting(Finding::rule).contains(e.getKey());
        }
        assertThat(findings("deploy/x.yaml", "  DB_PASSWORD: " + "example-not-a-real-password")).extracting(Finding::rule).contains("secret-assignment");
        assertThat(findings("deploy/x.sh", "APP_SECRET=" + "example-not-a-real-secret")).extracting(Finding::rule).contains("secret-assignment");
        assertThat(findings(".env", "")).extracting(Finding::rule).containsExactly("secret-file-name");
        assertThat(findings("deploy/id_ed25519", "")).extracting(Finding::rule).containsExactly("secret-file-name");
        assertThat(findings("certs/server.pem", "")).extracting(Finding::rule).containsExactly("secret-file-name");
    }

    /** What is not a value: references, placeholders, file mounts, comments, a Java assignment, the template's name. */
    @Test
    void the_scanner_leaves_references_and_placeholders_alone() {
        assertThat(findings("deploy/x.yaml", "      DB_PASSWORD_FILE: /run/secrets/db_password")).isEmpty();
        assertThat(findings("deploy/x.yaml", "      DB_PASSWORD: ${DB_PASSWORD}")).isEmpty();
        assertThat(findings("deploy/x.sh", ": \"${MIGRATE_PASSWORD:?MIGRATE_PASSWORD must be set}\"")).isEmpty();
        assertThat(findings(".env.example", "APP_SECRET=<replace-with-random-hex-secret>")).isEmpty();
        assertThat(findings("deploy/x.yaml", "# DB_PASSWORD: example-not-a-real-password")).isEmpty();
        assertThat(findings("docs/x.md", "postgresql://app:${DB_PASSWORD}@db:5432/x")).isEmpty();
        assertThat(findings("docs/x.md", "postgresql://app:<password>@db:5432/x")).isEmpty();
        assertThat(findings("web/src/X.java", "        String password = request.getParameter(\"password\");")).isEmpty();
        assertThat(findings("deploy/env.example", "")).isEmpty();
    }

    /** An exception covers one value in one file under one rule: the same value elsewhere, or another value there, is not excused. */
    @Test
    void an_exception_covers_exactly_its_file_rule_and_value() {
        Allowed e = EXCEPTIONS.get(0);
        Finding same = new Finding(e.path(), 1, e.rule(), e.sha256());
        assertThat(excused(same, e)).isTrue();
        assertThat(excused(new Finding("deploy/other.sh", 1, e.rule(), e.sha256()), e)).as("another file").isFalse();
        assertThat(excused(new Finding(e.path(), 1, "jwt", e.sha256()), e)).as("another rule").isFalse();
        assertThat(excused(new Finding(e.path(), 1, e.rule(), sha256("eyJ" + "another")), e)).as("another value").isFalse();
    }
}
