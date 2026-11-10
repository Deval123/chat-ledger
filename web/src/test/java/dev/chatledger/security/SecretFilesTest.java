package dev.chatledger.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Secrets from files (task 13, D6): what is accepted, what is refused, and that a refusal never shows the value. No database. */
class SecretFilesTest {

    @TempDir
    Path dir;

    private Path secret(String content, String mode) throws IOException {
        Path f = dir.resolve("s-" + System.nanoTime());
        Files.writeString(f, content);
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString(mode));
        return f;
    }

    @Test
    void a_file_replaces_the_variable_and_one_trailing_newline_is_removed() throws IOException {
        Path f = secret("hunter-value-1\n", "r--r-----");
        Map<String, String> env = SecretFiles.resolve(Map.of("X_FILE", f.toString(), "OTHER", "kept"), List.of("X"));
        assertThat(env).containsEntry("X", "hunter-value-1").containsEntry("OTHER", "kept").doesNotContainKey("X_FILE");
        assertThat(SecretFiles.resolve(Map.of("X", "plain"), List.of("X"))).containsEntry("X", "plain");
        assertThat(SecretFiles.resolve(Map.of(), List.of("X"))).doesNotContainKey("X");
    }

    @Test
    void each_unsafe_or_odd_file_is_refused_and_the_value_never_appears() throws IOException {
        String value = "do-not-print-this";
        Path everyone = secret(value, "r--r--r--");
        Path writable = secret(value, "rw-rw--w-");
        Path twoLines = secret(value + "\nsecond", "r--------");
        Path windows = secret(value + "\r\n", "r--------");
        Path empty = secret("\n", "r--------");
        Path large = secret("x".repeat(SecretFiles.MAX_BYTES + 1), "r--------");
        for (Path p : List.of(everyone, writable, twoLines, windows, empty, large, dir.resolve("missing"), dir)) {
            assertThatThrownBy(() -> SecretFiles.resolve(Map.of("X_FILE", p.toString()), List.of("X"))).as(p.toString())
                    .isInstanceOf(SecretFiles.Refused.class).hasMessageContaining("X_FILE").hasMessageNotContaining(value);
        }
        Path fine = secret(value, "r--r-----");
        assertThatThrownBy(() -> SecretFiles.resolve(Map.of("X", "also", "X_FILE", fine.toString()), List.of("X")))
                .hasMessageContaining("both X and X_FILE").hasMessageNotContaining(value).hasMessageNotContaining("also");
        assertThat(SecretFiles.resolve(Map.of("X_FILE", secret("exactly\n", "r--------").toString()), List.of("X"))).containsEntry("X", "exactly");
    }

    @Test
    void a_pipe_is_refused_without_being_read() throws Exception {
        // A FIFO is not a secret file: read with no writer it blocks forever, read with one it yields whatever the writer sends.
        // A writer is attached here (a separate process, stopped at the end), so a reader would get a value and never block.
        Path fifo = dir.resolve("fifo");
        assertThat(new ProcessBuilder("mkfifo", "-m", "0600", fifo.toString()).start().waitFor()).isZero();
        Process writer = new ProcessBuilder("sh", "-c", "printf from-a-pipe > \"$1\"", "sh", fifo.toString()).start();
        try {
            assertThatThrownBy(() -> SecretFiles.resolve(Map.of("X_FILE", fifo.toString()), List.of("X")))
                    .isInstanceOf(SecretFiles.Refused.class).hasMessageContaining("X_FILE does not name a readable file")
                    .hasMessageNotContaining("from-a-pipe");
        } finally {
            writer.destroyForcibly();
        }
    }
}
