package dev.chatledger.deploy;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static dev.chatledger.deploy.ComposeFileTest.list;
import static dev.chatledger.deploy.ComposeFileTest.map;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CI workflows (task 13, D1, D2, D11, D16 and the owner's change 1): every action pinned by a full commit SHA; the image built and
 * scanned by jobs that cannot write a package; the one job that can write runs only GitHub's artifact action and the Docker CLI, on
 * main only; the uptime check has no permission at all. No network: the files are read as YAML and as text.
 */
class WorkflowFileTest {

    private static final Path WORKFLOWS = Path.of("..", ".github", "workflows");
    /** {@code uses: owner/repo@<40 hex> # vX.Y.Z}: the SHA and the release it was checked against. */
    private static final Pattern USES = Pattern.compile("^\\s*(?:-\\s*)?uses:\\s*(\\S+)(.*)$");
    private static final Pattern PINNED = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+@[0-9a-f]{40}$");
    private static final Pattern RELEASE_COMMENT = Pattern.compile("^\\s+#\\s+v[0-9]+\\.[0-9]+\\.[0-9]+$");

    private static Map<String, Object> workflow(String file) throws IOException {
        // "on" is a YAML 1.1 boolean; the safe loader keeps it as the key true, which nothing here reads.
        Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(WORKFLOWS.resolve(file)));
        return map(loaded);
    }

    private static Map<String, Object> job(String name) throws IOException {
        return map(map(workflow("ci.yml").get("jobs")).get(name));
    }

    private static List<String> uses(Map<String, Object> job) {
        List<String> uses = new ArrayList<>();
        for (Object step : list(job.get("steps"))) {
            Object u = map(step).get("uses");
            if (u != null) {
                uses.add(String.valueOf(u));
            }
        }
        return uses;
    }

    private static String runs(Map<String, Object> job) {
        StringBuilder all = new StringBuilder();
        for (Object step : list(job.get("steps"))) {
            Object r = map(step).get("run");
            if (r != null) {
                all.append(r).append('\n');
            }
        }
        return all.toString();
    }

    @Test
    void every_action_is_pinned_by_a_full_commit_sha_with_its_release_named() throws IOException {
        int seen = 0;
        try (Stream<Path> files = Files.list(WORKFLOWS)) {
            for (Path file : files.toList()) {
                for (String line : Files.readAllLines(file)) {
                    Matcher m = USES.matcher(line);
                    if (m.matches()) {
                        seen++;
                        assertThat(m.group(1)).as(file.getFileName() + ": " + line.strip()).matches(PINNED);
                        assertThat(m.group(2)).as(file.getFileName() + ": the release the SHA was checked against").matches(RELEASE_COMMENT);
                    }
                    assertThat(line).as(file.getFileName() + ": no docker:// action").doesNotContain("docker://");
                }
            }
        }
        assertThat(seen).as("the check saw the actions").isGreaterThanOrEqualTo(9);
    }

    @Test
    void the_workflows_grant_least_privilege() throws IOException {
        assertThat(map(workflow("ci.yml").get("permissions"))).containsExactlyEntriesOf(Map.of("contents", "read"));
        assertThat(map(workflow("uptime.yml").get("permissions"))).as("uptime: no permission").isEmpty();
        assertThat(workflow("uptime.yml")).containsKey("permissions");
        Map<String, Object> jobs = map(workflow("ci.yml").get("jobs"));
        for (Map.Entry<String, Object> e : jobs.entrySet()) {
            Map<String, Object> permissions = map(map(e.getValue()).get("permissions"));
            if (e.getKey().equals("image-push")) {
                assertThat(permissions).containsOnly(Map.entry("contents", "read"), Map.entry("packages", "write"));
            } else {
                assertThat(permissions).as(e.getKey() + ": no packages permission").doesNotContainKey("packages")
                        .allSatisfy((k, v) -> assertThat(v).as(e.getKey() + " " + k).isEqualTo("read"));
            }
        }
    }

    @Test
    void the_build_job_builds_and_drills_but_never_logs_in_or_pushes() throws IOException {
        Map<String, Object> build = job("image-build");
        String run = runs(build);
        assertThat(run).contains("docker build").contains("sh deploy/check-image.sh chat-ledger:test")
                .contains("sudo sh deploy/test-stack.sh").contains("docker save chat-ledger:test -o chat-ledger.tar");
        assertThat(run).doesNotContain("docker login").doesNotContain("docker push").doesNotContain("secrets.");
        assertThat(run).doesNotContain("--build-arg DB_").doesNotContain("--secret");
        assertThat(String.valueOf(build.get("needs"))).contains("build-and-test");
    }

    @Test
    void the_scan_job_scans_the_archive_with_a_pinned_scanner_and_fails_on_fixable_high_findings() throws IOException {
        Map<String, Object> scan = job("image-scan");
        String run = runs(scan);
        assertThat(run).contains("aquasec/trivy:0.76.2@sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb")
                .contains("--input /work/chat-ledger.tar").contains("--severity HIGH,CRITICAL").contains("--ignore-unfixed")
                .contains("--exit-code 1");
        assertThat(run).doesNotContain("docker login").doesNotContain("docker push").doesNotContain("GITHUB_TOKEN");
        assertThat(run.split("aquasec/trivy", -1).length - 1).as("every scanner reference is the pinned one")
                .isEqualTo(run.split("aquasec/trivy:0\\.76\\.2@sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb", -1).length - 1)
                .isGreaterThanOrEqualTo(2);
        assertThat(uses(scan)).allMatch(u -> u.startsWith("actions/download-artifact@"));
        assertThat(run).as("the scanned archive is the built one").contains("test \"$loaded\" = \"$BUILT\"");
        assertThat(String.valueOf(map(list(scan.get("steps")).get(1)).get("env"))).contains("needs.image-build.outputs.image-id");
    }

    @Test
    void the_push_job_runs_no_third_party_code_and_only_on_main() throws IOException {
        Map<String, Object> push = job("image-push");
        assertThat(uses(push)).as("only GitHub's own artifact action").hasSize(1).allMatch(u -> u.startsWith("actions/download-artifact@"));
        String run = runs(push);
        assertThat(run).as("no scanner, no other image run here").doesNotContain("docker run").doesNotContain("trivy")
                .doesNotContain("curl").doesNotContain("wget").doesNotContain("pip ").doesNotContain("npm ");
        assertThat(run).contains("--password-stdin").contains("docker push").contains("docker logout ghcr.io").contains("test \"$loaded\" = \"$BUILT\"");
        assertThat(run).as("the token is never on a command line").doesNotContain("-p \"$TOKEN\"").doesNotContain("--password ");
        assertThat(String.valueOf(push.get("if"))).isEqualTo("github.event_name == 'push' && github.ref == 'refs/heads/main'");
        assertThat(list(push.get("needs"))).containsExactlyInAnyOrder("image-build", "image-scan");
        assertThat(run).as("pushed by commit, never latest").contains("chat-ledger:${GITHUB_SHA}").doesNotContain(":latest");
    }

    /**
     * Task 12b, register 178: the image job rehearses the upgrade a deploy makes, migration included, from the release that runs on the
     * VPS. That release is the one RUNBOOK's "Deployed state" names, so the rehearsal never starts from an older one unnoticed.
     */
    @Test
    void the_image_job_rehearses_the_upgrade_from_the_release_the_runbook_says_is_deployed() throws IOException {
        Map<String, Object> build = job("image-build");
        Map<String, Object> rehearsal = null;
        for (Object step : list(build.get("steps"))) {
            if (String.valueOf(map(step).get("run")).contains("deploy/test-upgrade.sh")) {
                rehearsal = map(step);
            }
        }
        assertThat(rehearsal).as("a step runs deploy/test-upgrade.sh").isNotNull();
        String run = String.valueOf(rehearsal.get("run"));
        assertThat(run).contains("docker pull --quiet \"$DEPLOYED\"").contains("sudo sh deploy/test-upgrade.sh \"$DEPLOYED\" chat-ledger:test")
                .doesNotContain("docker login").doesNotContain("docker push");
        String deployed = String.valueOf(map(rehearsal.get("env")).get("DEPLOYED"));
        Matcher digest = Pattern.compile("^ghcr\\.io/deval123/chat-ledger@(sha256:[0-9a-f]{64})$").matcher(deployed);
        assertThat(digest.matches()).as("pinned by digest: " + deployed).isTrue();
        String runbook = Files.readString(Path.of("..", "deploy", "RUNBOOK.md"));
        Matcher release = Pattern.compile("(?m)^\\| Release \\| `ghcr\\.io/deval123/chat-ledger@(sha256:[0-9a-f]{64})`").matcher(runbook);
        assertThat(release.find()).as("RUNBOOK's Deployed state names the release").isTrue();
        assertThat(digest.group(1)).as("the rehearsal starts from the deployed release").isEqualTo(release.group(1));
        List<String> names = new ArrayList<>();
        for (Object step : list(build.get("steps"))) {
            names.add(String.valueOf(map(step).get("name")));
        }
        assertThat(names.indexOf(String.valueOf(rehearsal.get("name")))).as("after the drill, before the archive is saved")
                .isGreaterThan(names.indexOf("Run the stack and the smoke script")).isLessThan(names.indexOf("Save the archive"));
    }

    /** Task 12a, D2 and the owner's condition 1: the browser job runs third-party code, so it can read source and nothing else. */
    @Test
    void the_browser_job_verifies_what_it_runs_keeps_the_sandbox_and_can_reach_nothing_else() throws IOException {
        Map<String, Object> a11y = job("a11y-browser");
        assertThat(a11y).as("runs beside the build, not after it").doesNotContainKey("needs");
        assertThat(map(a11y.get("permissions"))).containsExactlyEntriesOf(Map.of("contents", "read"));
        assertThat(uses(a11y)).as("checkout, Java, Node and the cache; no upload, so nothing leaves the job")
                .allMatch(u -> u.startsWith("actions/checkout@") || u.startsWith("actions/setup-java@")
                        || u.startsWith("actions/setup-node@") || u.startsWith("actions/cache@"))
                .anyMatch(u -> u.equals("actions/setup-node@49933ea5288caeca8642d1e84afbd3f7d6820020"));
        String run = runs(a11y);
        String env = String.valueOf(a11y.get("env"));
        assertThat(env).contains("CFT_VERSION=157.0.8103.50")
                .contains("CFT_SHA256=d17167bc092eb495a3b768c7467f27e35f625aa03834233a1663af67dcb668c9");
        assertThat(run).as("the browser is checked against its pin, over https only")
                .contains("echo \"${CFT_SHA256}  ${zip}\" | sha256sum -c -").contains("--proto '=https'");
        assertThat(run.indexOf("sha256sum -c")).as("checked before it is unpacked").isLessThan(run.indexOf("unzip"));
        assertThat(run).as("the sandbox stays on").contains("CHROME_DEVEL_SANDBOX=").contains("-m 4755").doesNotContain("no-sandbox");
        assertThat(Files.readString(Path.of("..", "tools", "browser-check", "a11y-walk.mjs")))
                .as("the walk never turns the sandbox off, and proves it is on").doesNotContain("--no-sandbox").contains("chrome://sandbox");
        String scanner = "aquasec/trivy:0.76.2@sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb";
        assertThat(run).contains(scanner + " fs --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1");
        assertThat(run.split("aquasec/trivy", -1).length).as("every scanner reference is the pinned one").isEqualTo(run.split(scanner, -1).length);
        assertThat(run.indexOf(scanner)).as("the lockfile is scanned before anything from it is installed").isLessThan(run.indexOf("npm ci"));
        assertThat(run).as("the test-only jsoup jar is scanned, in rootfs mode (fs mode does not read jars)")
                .contains("-Dartifact=\"org.jsoup:jsoup:${version}\"").contains("<jsoup.version>").contains(scanner + " \\\n  rootfs --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1");
        assertThat(run).contains("npm ci --ignore-scripts").doesNotContain("npm install").doesNotContain("npx ");
        assertThat(run).contains("-Dbrowser.demo=true").contains("node a11y-walk.mjs").contains("touch /tmp/browser-demo.done");
        assertThat(run).doesNotContain("docker login").doesNotContain("docker push").doesNotContain("docker build");
        assertThat(String.valueOf(a11y)).doesNotContain("secrets.");
    }

    /**
     * Register 167: Testcontainers' docker-java runs only where the tests run. Those jobs are GitHub-hosted (a fresh runner per job), use no
     * repository secret and can only read; every other Maven call skips the tests or only fetches a jar.
     */
    @Test
    void the_jobs_that_run_tests_have_no_secret_and_only_read() throws IOException {
        List<String> testing = new ArrayList<>();
        for (Map.Entry<String, Object> e : map(workflow("ci.yml").get("jobs")).entrySet()) {
            Map<String, Object> job = map(e.getValue());
            for (String line : runs(job).split("\n")) {
                if (line.contains("mvn ") && line.contains(" verify") && !line.contains("-DskipTests")) {
                    testing.add(e.getKey());
                    break;
                }
            }
        }
        assertThat(testing).containsExactlyInAnyOrder("build-and-test", "a11y-browser");
        for (String name : testing) {
            Map<String, Object> job = job(name);
            assertThat(String.valueOf(job)).as(name + ": no secret").doesNotContain("secrets.");
            assertThat(String.valueOf(job.get("runs-on"))).as(name + ": a GitHub-hosted runner").isEqualTo("ubuntu-24.04");
            assertThat(map(job.get("permissions"))).as(name + ": read only").allSatisfy((k, v) -> assertThat(v).isEqualTo("read"));
        }
    }

    @Test
    void secrets_are_used_only_where_they_are_needed() throws IOException {
        String ci = Files.readString(WORKFLOWS.resolve("ci.yml"));
        Matcher m = Pattern.compile("secrets\\.([A-Z_]+)").matcher(ci);
        List<String> used = new ArrayList<>();
        while (m.find()) {
            used.add(m.group(1));
        }
        assertThat(used).containsExactlyInAnyOrder("NVD_API_KEY", "GITHUB_TOKEN");
        assertThat(String.valueOf(job("image-push").get("steps"))).contains("secrets.GITHUB_TOKEN");
        assertThat(Files.readString(WORKFLOWS.resolve("uptime.yml"))).doesNotContain("secrets.").doesNotContain("uses:");
        assertThat(Files.readString(WORKFLOWS.resolve("digests.yml"))).doesNotContain("secrets.").doesNotContain("uses:")
                .doesNotContain("GITHUB_TOKEN").doesNotContain("git push").doesNotContain("gh ");
    }

    @Test
    void the_weekly_report_only_reads_and_scans_with_the_ci_scanner() throws IOException {
        Map<String, Object> digests = workflow("digests.yml");
        assertThat(digests).containsKey("permissions");
        assertThat(map(digests.get("permissions"))).as("no permission at all").isEmpty();
        Map<String, Object> jobs = map(digests.get("jobs"));
        assertThat(jobs.keySet()).containsExactlyInAnyOrder("report", "scan");
        for (Object job : jobs.values()) {
            assertThat(map(job)).doesNotContainKey("permissions");
            assertThat(uses(map(job))).as("no action").isEmpty();
        }
        assertThat(runs(map(jobs.get("report")))).contains("sh deploy/check-digests.sh");
        assertThat(runs(map(jobs.get("scan")))).contains("sh deploy/scan-runtime.sh \"ghcr.io/deval123/chat-ledger:");
        String scanner = "aquasec/trivy:0.76.2@sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb";
        assertThat(Files.readString(Path.of("..", "deploy", "scan-runtime.sh"))).as("the same pinned scanner as CI").contains(scanner);
        assertThat(String.valueOf(map(digests.get(true)).get("schedule"))).as("weekly").contains("cron=17 6 * * 1");
    }

    @Test
    void every_checkout_drops_its_credentials() throws IOException {
        for (Object job : map(workflow("ci.yml").get("jobs")).values()) {
            for (Object step : list(map(job).get("steps"))) {
                if (String.valueOf(map(step).get("uses")).startsWith("actions/checkout@")) {
                    assertThat(map(map(step).get("with"))).containsEntry("persist-credentials", false);
                }
            }
        }
    }
}
