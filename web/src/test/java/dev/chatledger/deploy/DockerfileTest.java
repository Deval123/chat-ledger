package dev.chatledger.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

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

    /** The one stage that is not an image: an empty seed for the Maven repository, which CI fills with {@code --build-context}. */
    static final String SEED = "FROM scratch AS m2seed";

    /** The stages built from an image (the seed stage left out). */
    private static List<String> bases() throws IOException {
        return instructions().stream().filter(l -> l.startsWith("FROM ") && !l.equals(SEED)).toList();
    }

    @Test
    void both_bases_are_pinned_by_digest() throws IOException {
        List<String> froms = bases();
        assertThat(froms).hasSize(2).allMatch(l -> FROM_PINNED.matcher(l).matches());
        assertThat(froms.get(1)).as("the runtime is distroless Debian 13, nonroot (debian12 carried fixable findings; register 152)")
                .startsWith("FROM gcr.io/distroless/java21-debian13:nonroot@sha256:");
        // One Java major on both sides: the build JDK compiles for 21 and the runtime JRE runs 21.
        assertThat(froms.get(0)).startsWith("FROM maven:3.9-eclipse-temurin-21@sha256:");
        assertThat(froms.get(1)).contains("/java21-");
        assertThat(Files.readString(ROOT.resolve("Dockerfile"))).as("no debian12 base left").doesNotContain("java21-debian12:nonroot@");
    }

    /**
     * The Maven seed (CI's restored repository) is mounted read-only by the build stage's dependency step and copied nowhere else, so no file
     * of it can reach the runtime stage; it is the only stage that is not a pinned image, and it is empty unless CI passes the context.
     */
    @Test
    void the_maven_seed_reaches_the_build_stage_only() throws IOException {
        List<String> all = instructions();
        assertThat(all.stream().filter(l -> l.startsWith("FROM ")).toList()).as("the seed stage comes first").first().isEqualTo(SEED);
        assertThat(all.stream().filter(l -> l.startsWith("FROM scratch"))).containsExactly(SEED);
        List<String> uses = all.stream().filter(l -> l.contains("m2seed") && !l.equals(SEED)).toList();
        assertThat(uses).as("one use: a bind mount in the build stage").hasSize(1)
                .allMatch(l -> l.startsWith("RUN --mount=type=bind,from=m2seed,target=/seed "));
        int build = all.indexOf(bases().get(0));
        int runtime = all.indexOf(bases().get(1));
        int use = all.indexOf(uses.get(0));
        assertThat(use).as("inside the build stage").isGreaterThan(build).isLessThan(runtime);
        assertThat(all.subList(runtime, all.size())).as("the runtime stage copies from the build stage only")
                .filteredOn(l -> l.contains("--from=")).allMatch(l -> l.contains("--from=build "));
        assertThat(all).as("what is still downloaded is checked and retried").anyMatch(l -> l.startsWith("ENV MAVEN_ARGS=\"--strict-checksums "));
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
        int runtime = all.indexOf(bases().get(1));
        List<String> stage = all.subList(runtime, all.size());
        assertThat(stage).contains("USER 65532:65532", "ENTRYPOINT [\"java\", \"-jar\", \"/app/app.jar\"]", "COPY --from=build --chown=0:0 /out /app");
        assertThat(stage).as("nothing runs in the runtime stage").noneMatch(l -> l.startsWith("RUN "));
        assertThat(all).contains(" && chmod 0444 /out/app.jar");
        assertThat(stage.get(stage.size() - 1)).startsWith("ENTRYPOINT ");
        assertThat(stage).filteredOn(l -> l.startsWith("USER ")).containsExactly("USER 65532:65532");
    }

    /**
     * Task 12a, the owner's condition 1, at the source: the browser tool is never in the build context, the Dockerfile never copies it or
     * runs Node, and jsoup is test scope in every POM that names it. CI's {@code deploy/check-image.sh} checks the built image itself.
     */
    @Test
    void nothing_of_the_ci_browser_check_can_enter_the_image() throws Exception {
        List<String> ignore = Files.readAllLines(ROOT.resolve(".dockerignore")).stream().filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
        assertThat(ignore).filteredOn(l -> l.startsWith("!"))
                .noneMatch(l -> l.startsWith("!tools") || l.contains("node_modules") || l.startsWith("!**") || l.startsWith("!*"));
        for (String line : instructions()) {
            assertThat(line).as(line).doesNotContain("tools/").doesNotContain("npm ").doesNotContain("node ").doesNotContain("puppeteer")
                    .doesNotContain("axe-core").doesNotContainIgnoringCase("chrome").doesNotContain("jsoup");
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        int named = 0;
        int containerLibs = 0;
        List<Path> poms;
        try (var walk = Files.walk(ROOT, 2)) {
            poms = walk.filter(p -> p.getFileName().toString().equals("pom.xml")).toList();
        }
        assertThat(poms).as("the root and the module POMs").hasSizeGreaterThanOrEqualTo(8);
        for (Path pom : poms) {
            NodeList dependencies = factory.newDocumentBuilder().parse(pom.toFile()).getElementsByTagName("dependency");
            for (int i = 0; i < dependencies.getLength(); i++) {
                Element dependency = (Element) dependencies.item(i);
                if ("org.jsoup".equals(text(dependency, "groupId"))) {
                    named++;
                    assertThat(text(dependency, "scope")).as(pom + ": jsoup is test scope").isEqualTo("test");
                }
                // Register 167: the docker-java findings are accepted because Testcontainers never leaves test scope.
                String group = text(dependency, "groupId");
                if (("org." + "test" + "containers").equals(group) || "com.github.docker-java".equals(group)) {
                    containerLibs++;
                    String scope = text(dependency, "scope");
                    assertThat(scope).as(pom + ": " + text(dependency, "artifactId") + " is test scope (or an imported BOM)")
                            .isIn("test", "import");
                }
            }
        }
        assertThat(named).as("the root's managed entry and web's").isEqualTo(2);
        assertThat(containerLibs).as("the check saw the container-library entries").isGreaterThanOrEqualTo(3);
    }

    private static String text(Element parent, String child) {
        NodeList found = parent.getElementsByTagName(child);
        return found.getLength() == 0 ? null : found.item(0).getTextContent().strip();
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
