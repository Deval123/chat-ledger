package dev.chatledger.deploy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two read-only report scripts of the weekly "Pinned digests" workflow (task 13), run as written against a fake {@code docker} on
 * the PATH, so no Docker and no network are needed: {@code check-digests.sh} finds every pinned image in the repository and says
 * CURRENT, STALE or UNKNOWN for each, failing on anything but CURRENT; {@code scan-runtime.sh} scans every image the compose file pins
 * plus the application image it is given, and fails when any scan fails.
 */
class ReportScriptsTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Pattern PIN = Pattern.compile("([a-z0-9][a-z0-9./_-]*:[A-Za-z0-9][A-Za-z0-9._-]*)@(sha256:[0-9a-f]{64})");
    private static final List<String> FILES = List.of("Dockerfile", "deploy/compose.yaml", "deploy/hops-echo.yaml", "deploy/test-stack.sh",
            ".github/workflows/ci.yml", "web/src/test/java/dev/chatledger/ledger/PostgresRelease.java",
            "web/src/test/java/dev/chatledger/deploy/MakeSecretsScriptIT.java");

    @TempDir
    Path tmp;

    record Run(int code, String out) {
    }

    /** Every pin in the scanned files, read here independently of the script. */
    private static Map<String, String> pins() throws IOException {
        Map<String, String> pins = new LinkedHashMap<>();
        for (String f : FILES) {
            Matcher m = PIN.matcher(Files.readString(ROOT.resolve(f)));
            while (m.find()) {
                pins.put(m.group(1), m.group(2));
            }
        }
        return pins;
    }

    /** A fake docker: answers {@code buildx imagetools inspect NAME} from a map file, and records {@code run ...} calls. */
    private Path fakeDocker(String body) throws IOException {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path docker = bin.resolve("docker");
        Files.writeString(docker, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(docker, PosixFilePermissions.fromString("rwxr-xr-x"));
        return bin;
    }

    private Run run(Path bin, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command).directory(ROOT.toFile()).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":/usr/bin:/bin");
        pb.environment().put("FAKE_DIR", tmp.toString());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(p.waitFor(), out);
    }

    private Run digests(Map<String, String> registry) throws Exception {
        StringBuilder map = new StringBuilder();
        registry.forEach((name, digest) -> map.append(name).append(' ').append(digest).append('\n'));
        Files.writeString(tmp.resolve("registry"), map.toString());
        Path bin = fakeDocker("""
                [ "$1 $2 $3" = "buildx imagetools inspect" ] || exit 9
                d=$(awk -v n="$4" '$1 == n { print $2 }' "$FAKE_DIR/registry")
                [ -n "$d" ] || exit 1
                printf 'Name:      %s\\nMediaType: x\\nDigest:    %s\\n' "$4" "$d"
                """);
        return run(bin, "sh", "deploy/check-digests.sh");
    }

    @Test
    void every_pin_is_found_and_current_when_its_tag_has_not_moved() throws Exception {
        Map<String, String> pins = pins();
        assertThat(pins.keySet()).as("the pins this repository has")
                .contains("gcr.io/distroless/java21-debian13:nonroot", "maven:3.9-eclipse-temurin-21", "postgres:16.15-alpine",
                        "cloudflare/cloudflared:2026.10.7", "aquasec/trivy:0.76.2", "curlimages/curl:8.23.0", "traefik/whoami:v1.12.0",
                        "alpine:3.22.8");
        Run r = digests(pins);
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out().lines().filter(l -> l.startsWith("CURRENT "))).hasSize(pins.size());
        assertThat(r.out()).contains("Stale: 0. Unknown: 0.");
    }

    @Test
    void a_moved_tag_is_reported_stale_and_fails_the_run() throws Exception {
        Map<String, String> registry = new LinkedHashMap<>(pins());
        String moved = "sha256:" + "1".repeat(64);
        registry.put("gcr.io/distroless/java21-debian13:nonroot", moved);
        Run r = digests(registry);
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.out()).contains("STALE   gcr.io/distroless/java21-debian13:nonroot pinned sha256:d978eefd")
                .contains("the tag now points to " + moved).contains("Stale: 1. Unknown: 0.");
    }

    @Test
    void a_registry_that_cannot_be_read_is_unknown_and_fails_the_run() throws Exception {
        Map<String, String> registry = new LinkedHashMap<>(pins());
        registry.remove("postgres:16.15-alpine");
        Run r = digests(registry);
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.out()).contains("UNKNOWN postgres:16.15-alpine").contains("Unknown: 1.");
    }

    private static final String APP = "ghcr.io/deval123/chat-ledger@sha256:" + "2".repeat(64);

    /**
     * The real scan-runtime.sh and the real compose files, in a sandbox where deploy/check-gosu.sh is a stub that passes or fails on
     * demand (check-gosu.sh itself runs against the real image in CheckGosuIT). The fake docker records each scan and fails the scan of
     * the image named in FAKE_DIR/bad.
     */
    private Run scanRuntime(boolean gosuEvidence, String... args) throws Exception {
        return scanRuntime(gosuEvidence, true, args);
    }

    /** As above; the listing runs (Trivy's template output) print FAKE_DIR/list-<repository> when it exists, and fail on FAKE_DIR/bad. */
    private Run scanRuntime(boolean gosuEvidence, boolean cloudflaredEvidence, String... args) throws Exception {
        Path deploy = Files.createDirectories(tmp.resolve("work/deploy"));
        for (String f : List.of("scan-runtime.sh", "compose.yaml", "hops-echo.yaml")) {
            Files.copy(ROOT.resolve("deploy").resolve(f), deploy.resolve(f), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(deploy.resolve("check-gosu.sh"), gosuEvidence ? "echo stub evidence passed\nexit 0\n" : "echo stub evidence failed\nexit 1\n");
        Files.writeString(deploy.resolve("check-cloudflared.sh"), cloudflaredEvidence ? "exit 0\n" : "exit 1\n");
        Path bin = fakeDocker("""
                [ "$1" = run ] || exit 9
                for a in "$@"; do last="$a"; done
                printf '%s\\n' "$*" >> "$FAKE_DIR/calls"
                [ -f "$FAKE_DIR/bad" ] && [ "$last" = "$(cat "$FAKE_DIR/bad")" ] && exit 1
                case "$*" in
                    *"--format template"*)
                        repo="${last%%[:@]*}"; f="$FAKE_DIR/list-$(echo "$repo" | tr '/' '_')"
                        [ -f "$f" ] && cat "$f"
                        exit 0 ;;
                esac
                exit 0
                """);
        Files.deleteIfExists(tmp.resolve("calls"));
        String[] command = new String[args.length + 2];
        command[0] = "sh";
        command[1] = "deploy/scan-runtime.sh";
        System.arraycopy(args, 0, command, 2, args.length);
        ProcessBuilder pb = new ProcessBuilder(command).directory(tmp.resolve("work").toFile()).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":/usr/bin:/bin");
        pb.environment().put("FAKE_DIR", tmp.toString());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(p.waitFor(), out);
    }

    private List<String> calls() throws IOException {
        return Files.exists(tmp.resolve("calls")) ? Files.readAllLines(tmp.resolve("calls")) : List.of();
    }

    @Test
    void the_runtime_scan_covers_the_four_images_and_skips_gosu_only_with_its_evidence() throws Exception {
        Map<String, String> pins = pins();
        String postgres = "postgres:16.15-alpine@" + pins.get("postgres:16.15-alpine");
        Run clean = scanRuntime(true, APP);
        assertThat(clean.code()).as(clean.out()).isZero();
        List<String> calls = calls();
        assertThat(calls).hasSize(4).allMatch(l -> l.contains("aquasec/trivy:0.76.2@" + pins.get("aquasec/trivy:0.76.2"))
                && l.contains("--severity HIGH,CRITICAL") && l.contains("--ignore-unfixed"));
        assertThat(calls).filteredOn(l -> !l.contains("--format template")).as("the full scans fail on a finding")
                .hasSize(2).allMatch(l -> l.contains("--exit-code 1"));
        assertThat(String.join("\n", calls)).contains(APP).contains(postgres)
                .contains("cloudflare/cloudflared:2026.10.7@" + pins.get("cloudflare/cloudflared:2026.10.7"))
                .contains("traefik/whoami:v1.12.0@" + pins.get("traefik/whoami:v1.12.0"));
        assertThat(calls).filteredOn(l -> l.contains("--skip-files")).as("only PostgreSQL, only gosu")
                .singleElement().satisfies(l -> assertThat(l).endsWith("--skip-files /usr/local/bin/gosu " + postgres));
        assertThat(clean.out()).contains("ACCEPTED the findings in /usr/local/bin/gosu").contains("Images with findings: 0.");

        Run noEvidence = scanRuntime(false, APP);
        assertThat(calls()).as("without the evidence, gosu is scanned like everything else").noneMatch(l -> l.contains("--skip-files"));
        assertThat(noEvidence.out()).contains("NOT ACCEPTED").doesNotContain("ACCEPTED the findings");
    }

    @Test
    void a_finding_in_any_image_fails_the_run_and_the_application_image_is_required() throws Exception {
        Map<String, String> pins = pins();
        Files.writeString(tmp.resolve("bad"), "cloudflare/cloudflared:2026.10.7@" + pins.get("cloudflare/cloudflared:2026.10.7"));
        Run finding = scanRuntime(true, APP);
        assertThat(finding.code()).isEqualTo(1);
        assertThat(finding.out()).contains("FINDINGS cloudflare/cloudflared:2026.10.7").contains("Images with findings: 1.");
        Files.delete(tmp.resolve("bad"));
        assertThat(scanRuntime(true).code()).as("no application image").isEqualTo(2);
        assertThat(scanRuntime(true, "docker.io/someone/else:1").code()).as("another repository").isEqualTo(2);
        assertThat(calls()).as("nothing scanned on a usage error").isEmpty();
    }

    private void findings(String repository, String... lines) throws IOException {
        Files.writeString(tmp.resolve("list-" + repository.replace('/', '_')), String.join("\n", lines) + "\n");
    }

    @Test
    void cloudflared_passes_only_on_the_covered_findings_and_only_with_its_evidence() throws Exception {
        findings("cloudflare/cloudflared", "os-pkgs|debian|zlib1g|CVE-2026-76844", "os-pkgs|debian|zlib1g|CVE-2026-85091",
                "lang-pkgs|gobinary|golang.org/x/net|CVE-2026-78661");
        Run covered = scanRuntime(true, true, APP);
        assertThat(covered.code()).as(covered.out()).isZero();
        assertThat(covered.out()).contains("ACCEPTED (check-cloudflared.sh passed in this run, register 156): os-pkgs|debian|zlib1g|CVE-2026-85091")
                .contains("register 156): lang-pkgs|gobinary|golang.org/x/net|CVE-2026-78661").doesNotContain("NOT COVERED");

        findings("cloudflare/cloudflared", "os-pkgs|debian|zlib1g|CVE-2026-76844", "lang-pkgs|gobinary|golang.org/x/net|CVE-2026-99999");
        Run otherNet = scanRuntime(true, true, APP);
        assertThat(otherNet.code()).as("another x/net finding: its package may be linked").isEqualTo(1);
        assertThat(otherNet.out()).contains("NOT COVERED: lang-pkgs|gobinary|golang.org/x/net|CVE-2026-99999");

        findings("cloudflare/cloudflared", "os-pkgs|debian|libssl3|CVE-2026-11111");
        assertThat(scanRuntime(true, true, APP).code()).as("another OS package").isEqualTo(1);

        findings("cloudflare/cloudflared", "os-pkgs|debian|zlib1g|CVE-2026-76844");
        String cloudflared = "cloudflare/cloudflared:2026.10.7@" + pins().get("cloudflare/cloudflared:2026.10.7");
        Run noEvidence = scanRuntime(true, false, APP);
        assertThat(noEvidence.out()).contains("NOT ACCEPTED: check-cloudflared.sh failed");
        assertThat(calls()).as("without the evidence, cloudflared gets the failing scan")
                .anyMatch(l -> l.endsWith(cloudflared) && l.contains("--exit-code 1") && !l.contains("--format template"));
    }

    @Test
    void the_hops_echo_is_listed_not_failed_but_must_still_be_read() throws Exception {
        findings("traefik/whoami", "lang-pkgs|gobinary|stdlib|CVE-2026-33818");
        Run listed = scanRuntime(true, true, APP);
        assertThat(listed.code()).as(listed.out()).isZero();
        assertThat(listed.out()).contains("LISTED (time-boxed to the hops measurement, register 157): lang-pkgs|gobinary|stdlib|CVE-2026-33818");
        Files.writeString(tmp.resolve("bad"), "traefik/whoami:v1.12.0@" + pins().get("traefik/whoami:v1.12.0"));
        Run unreadable = scanRuntime(true, true, APP);
        assertThat(unreadable.code()).isEqualTo(1);
        assertThat(unreadable.out()).contains("FINDINGS traefik/whoami:v1.12.0");
    }
}
