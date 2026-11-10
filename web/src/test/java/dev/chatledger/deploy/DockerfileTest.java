package dev.chatledger.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code Dockerfile} and {@code .dockerignore} (task 13, D1): both bases pinned by digest; no secret can enter the build (no build
 * argument or variable named like one, no secret mount, an allowlist build context that never sends an environment file or a key);
 * the runtime stage runs as uid 65532 with the jar as its only entrypoint. CI's {@code deploy/check-image.sh} checks the built image.
 */
class DockerfileTest {

    private static final Path ROOT = Path.of("..");
    private static final Pattern FROM_PINNED = Pattern.compile("^FROM [a-z0-9./_-]+:[A-Za-z0-9._-]+@sha256:[0-9a-f]{64}( AS [a-z]+)?$");

    private static List<String> instructions() throws IOException {
        return Files.readAllLines(ROOT.resolve("Dockerfile")).stream().filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
    }

    @Test
    void both_bases_are_pinned_by_digest() throws IOException {
        List<String> froms = instructions().stream().filter(l -> l.startsWith("FROM ")).toList();
        assertThat(froms).hasSize(2).allMatch(l -> FROM_PINNED.matcher(l).matches());
        assertThat(froms.get(1)).as("the runtime is distroless Debian 13, nonroot (debian12 carried fixable findings; register 152)")
                .startsWith("FROM gcr.io/distroless/java21-debian13:nonroot@sha256:");
        // One Java major on both sides: the build JDK compiles for 21 and the runtime JRE runs 21.
        assertThat(froms.get(0)).startsWith("FROM maven:3.9-eclipse-temurin-21@sha256:");
        assertThat(froms.get(1)).contains("/java21-");
        assertThat(Files.readString(ROOT.resolve("Dockerfile"))).as("no debian12 base left").doesNotContain("java21-debian12:nonroot@");
    }

    @Test
    void no_secret_can_enter_the_build() throws IOException {
        Pattern secretish = Pattern.compile("(?i).*(password|secret|token|_key|credential).*");
        for (String line : instructions()) {
            if (line.startsWith("ARG ") || line.startsWith("ENV ")) {
                assertThat(line).as(line).doesNotMatch(secretish);
            }
            assertThat(line).as(line).doesNotContain("--mount=type=secret").doesNotContain("--mount=type=ssh").doesNotContain("ADD ");
        }
        assertThat(instructions().stream().filter(l -> l.startsWith("ARG "))).containsExactly("ARG REVISION=unknown");
    }

    @Test
    void the_runtime_runs_as_65532_and_owns_nothing_it_runs() throws IOException {
        List<String> all = instructions();
        int runtime = all.indexOf(all.stream().filter(l -> l.startsWith("FROM ")).toList().get(1));
        List<String> stage = all.subList(runtime, all.size());
        assertThat(stage).contains("USER 65532:65532", "ENTRYPOINT [\"java\", \"-jar\", \"/app/app.jar\"]", "COPY --from=build --chown=0:0 /out /app");
        assertThat(stage).as("nothing runs in the runtime stage").noneMatch(l -> l.startsWith("RUN "));
        assertThat(all).contains(" && chmod 0444 /out/app.jar");
        assertThat(stage.get(stage.size() - 1)).startsWith("ENTRYPOINT ");
        assertThat(stage).filteredOn(l -> l.startsWith("USER ")).containsExactly("USER 65532:65532");
    }

    @Test
    void the_build_context_is_an_allowlist_that_sends_no_environment_file_or_key() throws IOException {
        List<String> ignore = Files.readAllLines(ROOT.resolve(".dockerignore")).stream().filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
        assertThat(ignore.get(0)).as("everything is excluded first").isEqualTo("*");
        assertThat(ignore).contains("**/.env*", "**/*.pem", "**/id_*", "**/target");
        int lastAllow = 0;
        for (int i = 0; i < ignore.size(); i++) {
            String line = ignore.get(i);
            if (line.startsWith("!")) {
                lastAllow = i;
                assertThat(line).as(line).matches("!(pom\\.xml|[a-z-]+/pom\\.xml|[a-z-]+/src/\\*\\*|web/third-party/\\*\\*)");
            }
        }
        assertThat(ignore.indexOf("**/.env*")).as("the exclusions come after every allow, so they win").isGreaterThan(lastAllow);
        // Every source the build stage copies is allowed into the context.
        for (String line : instructions()) {
            if (line.startsWith("COPY ") && !line.contains("--from=")) {
                String source = line.split(" ")[1];
                String allowed = source.endsWith("pom.xml") ? "!" + source : "!" + source + "/**";
                assertThat(ignore).as(line).contains(allowed);
            }
        }
    }
}
