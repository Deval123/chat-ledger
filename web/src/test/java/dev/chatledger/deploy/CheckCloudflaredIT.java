package dev.chatledger.deploy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The evidence behind the accepted cloudflared findings (register 156): {@code deploy/check-cloudflared.sh}.
 *
 * <p>On both architectures of the image {@code compose.yaml} pins, every check passes: the entrypoint is {@code cloudflared} and compose
 * does not override it; the binary has no program interpreter (no dynamic loader), so the image's libz is never
 * loaded; it keeps its symbol table; and no symbol of {@code golang.org/x/net/html} is in it.
 *
 * <p>Each check can fail. Twins of the pinned image, with {@code /usr/local/bin/cloudflared} replaced by a small Go program built
 * locally (no network: a local module stands in for {@code golang.org/x/net}), must be refused: a dynamically linked one, a stripped one
 * (absence would prove nothing), one with {@code x/net/html} linked, and one whose entrypoint is not {@code cloudflared}; so must a
 * compose file that overrides the tunnel's entrypoint. A static twin without the package passes, so each refusal is for its own reason.
 * Needs Docker (the integration phase); the twin images are removed afterwards.
 */
class CheckCloudflaredIT {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final String TOOLS = "golang:1.25-bookworm@sha256:3b4a11519ad929d1e1d261a12cff056f0c85b735253d7d861346b9c6f8b36437";
    private static final String STATIC = "chat-ledger-cloudflared-test:static";
    private static final String DYNAMIC = "chat-ledger-cloudflared-test:dynamic";
    private static final String STRIPPED = "chat-ledger-cloudflared-test:stripped";
    private static final String HTML = "chat-ledger-cloudflared-test:html";
    private static final String ENTRY = "chat-ledger-cloudflared-test:entrypoint";

    @TempDir
    static Path tmp;

    record Run(int code, String out) {
    }

    private static Run run(Path dir, String... command) throws Exception {
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(10, TimeUnit.MINUTES)).as("finished: " + String.join(" ", command)).isTrue();
        return new Run(p.exitValue(), out);
    }

    private static String pinnedImage() throws IOException {
        Matcher m = Pattern.compile("(?m)^    image: (cloudflare/cloudflared:\\S+@sha256:[0-9a-f]{64})$")
                .matcher(Files.readString(ROOT.resolve("deploy/compose.yaml")));
        assertThat(m.find()).as("compose.yaml pins cloudflared").isTrue();
        return m.group(1);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static void twin(Path context, String tag, String cgo, String ldflags, String program) throws Exception {
        Run r = run(context, "docker", "build", "-q", "-t", tag, "--build-arg", "CGO=" + cgo, "--build-arg", "LDFLAGS=" + ldflags,
                "--build-arg", "PROGRAM=" + program, ".");
        assertThat(r.code()).as(r.out()).isZero();
    }

    @BeforeAll
    static void twins() throws Exception {
        Path ctx = tmp.resolve("twin");
        write(ctx.resolve("go.mod"), "module twin\n\ngo 1.25\n\nrequire golang.org/x/net v0.0.0\n\nreplace golang.org/x/net => ./xnet\n");
        write(ctx.resolve("xnet/go.mod"), "module golang.org/x/net\n\ngo 1.25\n");
        write(ctx.resolve("xnet/html/html.go"), "package html\n\n// Parse stands in for the real package: only its symbol names matter.\n//\n//go:noinline\n"
                + "func Parse(s string) int { return len(s) * 7 }\n");
        String body = "\nfunc main() {\n\tfmt.Println(%s)\n\tif len(os.Args) > 5 {\n\t\t_ = http.ListenAndServe(\":0\", nil)\n\t}\n}\n";
        write(ctx.resolve("cmd/plain/main.go"), "package main\n\nimport (\n\t\"fmt\"\n\t\"net/http\"\n\t\"os\"\n)\n" + body.formatted("len(os.Args)"));
        write(ctx.resolve("cmd/withhtml/main.go"), "package main\n\nimport (\n\t\"fmt\"\n\t\"net/http\"\n\t\"os\"\n\n\t\"golang.org/x/net/html\"\n)\n"
                + body.formatted("html.Parse(os.Args[0])"));
        write(ctx.resolve("Dockerfile"), "FROM " + TOOLS + " AS build\nARG CGO\nARG LDFLAGS\nARG PROGRAM\nWORKDIR /src\nCOPY . .\n"
                + "ENV GOTOOLCHAIN=local GOPROXY=off GOFLAGS=-mod=mod\n"
                + "RUN CGO_ENABLED=$CGO go build $LDFLAGS -o /out/cloudflared ./cmd/$PROGRAM\n"
                + "FROM " + pinnedImage() + "\nCOPY --from=build /out/cloudflared /usr/local/bin/cloudflared\n");
        twin(ctx, STATIC, "0", "-trimpath", "plain");
        twin(ctx, DYNAMIC, "1", "-trimpath", "plain");
        twin(ctx, STRIPPED, "0", "-ldflags=-s", "plain");
        twin(ctx, HTML, "0", "-trimpath", "withhtml");
        Path entry = tmp.resolve("entry");
        write(entry.resolve("Dockerfile"), "FROM " + pinnedImage() + "\nENTRYPOINT [\"cloudflared\", \"--loglevel\", \"debug\"]\n");
        Run r = run(entry, "docker", "build", "-q", "-t", ENTRY, ".");
        assertThat(r.code()).as(r.out()).isZero();
    }

    @AfterAll
    static void removeTwins() throws Exception {
        run(ROOT, "docker", "image", "rm", "-f", STATIC, DYNAMIC, STRIPPED, HTML, ENTRY);
    }

    /** check-cloudflared.sh from a sandbox holding a copy of the script and of compose.yaml, the latter edited by {@code edit}. */
    private static Run sandbox(java.util.function.UnaryOperator<String> edit, String... args) throws Exception {
        Path work = Files.createTempDirectory(tmp, "work");
        Path deploy = Files.createDirectories(work.resolve("deploy"));
        Files.copy(ROOT.resolve("deploy/check-cloudflared.sh"), deploy.resolve("check-cloudflared.sh"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(deploy.resolve("compose.yaml"), edit.apply(Files.readString(ROOT.resolve("deploy/compose.yaml"))));
        List<String> command = new ArrayList<>(List.of("sh", "deploy/check-cloudflared.sh"));
        command.addAll(List.of(args));
        return run(work, command.toArray(String[]::new));
    }

    @Test
    void the_evidence_holds_for_both_architectures_of_the_pinned_image() throws Exception {
        Run r = run(ROOT, "sh", "deploy/check-cloudflared.sh");
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out()).contains("PASS the tunnel service runs the image's own entrypoint");
        for (String arch : List.of("amd64", "arm64")) {
            assertThat(r.out()).contains("PASS " + arch + ": the entrypoint is cloudflared")
                    .contains("PASS " + arch + ": statically linked (no program interpreter, so no dynamic loader): libz is never loaded")
                    .contains("PASS " + arch + ": the symbol table is there").contains("PASS " + arch + ": golang.org/x/net/html is not linked");
        }
        assertThat(r.out()).contains("Failures: 0").doesNotContain("FAIL ");
    }

    @Test
    void a_static_twin_without_the_package_passes_so_each_refusal_below_has_its_own_reason() throws Exception {
        Run r = sandbox(c -> c, STATIC);
        assertThat(r.code()).as(r.out()).isZero();
    }

    @Test
    void a_dynamically_linked_binary_is_refused() throws Exception {
        Run r = sandbox(c -> c, DYNAMIC);
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.out()).contains("FAIL local: not static").contains("PASS local: golang.org/x/net/html is not linked");
    }

    @Test
    void a_stripped_binary_is_refused_because_absence_would_prove_nothing() throws Exception {
        Run r = sandbox(c -> c, STRIPPED);
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.out()).contains("FAIL local: only [").contains("stripped, so absence proves nothing");
    }

    @Test
    void a_binary_with_the_html_package_is_refused() throws Exception {
        Run r = sandbox(c -> c, HTML);
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.out()).contains("FAIL local: golang.org/x/net/html is linked").contains("PASS local: statically linked");
    }

    @Test
    void another_entrypoint_in_the_image_or_in_compose_is_refused() throws Exception {
        Run image = sandbox(c -> c, ENTRY);
        assertThat(image.code()).isEqualTo(1);
        assertThat(image.out()).contains("FAIL local: the entrypoint is [[\"cloudflared\",\"--loglevel\",\"debug\"]]");
        Run compose = sandbox(c -> c.replaceFirst("(?m)^(  tunnel:\n)", "$1    entrypoint: [\"/usr/local/bin/cloudflared\"]\n"), STATIC);
        assertThat(compose.code()).isEqualTo(1);
        assertThat(compose.out()).contains("FAIL the tunnel service overrides the entrypoint");
    }
}
