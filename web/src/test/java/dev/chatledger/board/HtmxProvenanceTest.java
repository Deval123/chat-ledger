package dev.chatledger.board;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * htmx is the one piece of third-party code that runs in the signed-in page. The file is the one from the official release, byte for byte;
 * these constants were computed from the release archive (see {@code web/third-party/htmx/PROVENANCE.md}), and the build fails if the file
 * changes without them being changed on purpose.
 */
class HtmxProvenanceTest {

    private static final String VERSION = "2.0.11";
    private static final String SHA256 = "d6fdc75f204e6bdefa99b69bf1e6d4ac69b8a364f77929f45c13476b4000f717";
    private static final String ARCHIVE_SHA256 = "665124df2a65996a8fb18152ef214cada197ffba63f498b5611566c4cbc0ba1b";
    private static final long SIZE = 52_182;
    private static final Path FILE = Path.of("src", "main", "resources", "static", "htmx-" + VERSION + ".min.js");
    private static final Path NOTE = Path.of("third-party", "htmx", "PROVENANCE.md");
    private static final Path LICENCE = Path.of("third-party", "htmx", "LICENSE");

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void the_file_is_the_release_byte_for_byte() throws Exception {
        byte[] bytes = Files.readAllBytes(FILE);
        assertThat(bytes).hasSize((int) SIZE);
        assertThat(sha256(bytes)).isEqualTo(SHA256);
    }

    @Test
    void the_file_says_the_version_its_name_says() throws IOException {
        String text = Files.readString(FILE, StandardCharsets.UTF_8);
        assertThat(text).contains("version:\"" + VERSION + "\"");
        assertThat(text).as("no address in the library: it makes no request of its own").doesNotContain("http://").doesNotContain("https://");
    }

    @Test
    void the_provenance_note_names_the_version_the_hashes_and_the_licence() throws IOException {
        String note = Files.readString(NOTE);
        assertThat(note).contains("2.0.11").contains(SHA256).contains(ARCHIVE_SHA256).contains("Zero-Clause BSD")
                .contains("github.com/bigskysoftware/htmx/archive/refs/tags/v" + VERSION + ".tar.gz").contains("52,182");
    }

    @Test
    void the_licence_is_the_zero_clause_bsd_text() throws IOException {
        String licence = Files.readString(LICENCE);
        assertThat(licence).startsWith("Zero-Clause BSD").contains("Permission to use, copy, modify, and/or distribute this software for")
                .contains("THE SOFTWARE IS PROVIDED");
    }

    @Test
    void it_is_the_only_script_in_the_static_files() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "resources", "static"))) {
            List<String> scripts = files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".js")).toList();
            assertThat(scripts).containsExactly("htmx-" + VERSION + ".min.js");
        }
    }

    @Test
    void the_check_would_notice_a_changed_byte() throws Exception {
        byte[] bytes = Files.readAllBytes(FILE);
        bytes[bytes.length / 2] ^= 1;
        assertThat(sha256(bytes)).isNotEqualTo(SHA256);
    }
}
