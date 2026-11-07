package dev.chatledger.extraction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural proof, not a timing test: the extraction code cannot build a regular expression, a format string, a
 * class, a script or a process from message text, because it does not use any of those facilities at all. The scanner
 * is first shown to catch invented violations.
 *
 * <p>The one allowed use of a pattern-taking call is {@code split("\n", -1)} with a literal, single-character argument.
 *
 * <p>Requirement: 8 (deterministic parsers), 9.2 (validate every input). Task 4.1.
 */
class NoInputPatternsTest {

    private static final List<String> FORBIDDEN = List.of(
            "java.util.regex", "Pattern.", "Matcher", ".matches(", ".replaceAll(", ".replaceFirst(", "String.format(", ".formatted(",
            "Class.forName", "getMethod(", "getDeclaredMethod(", "ScriptEngine", "ObjectInputStream", "Runtime.getRuntime",
            "ProcessBuilder", "MethodHandles", "System.getenv", "System.getProperty", "URLClassLoader", "Expression", "XMLDecoder");

    static List<String> violations(String file, String source) {
        String code = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        List<String> found = new ArrayList<>();
        for (String bad : FORBIDDEN) {
            if (code.contains(bad)) {
                found.add(file + " uses " + bad);
            }
        }
        int at = code.indexOf(".split(");
        while (at >= 0) {
            String rest = code.substring(at + ".split(".length());
            if (!rest.startsWith("\"\\n\", -1)")) {
                found.add(file + " calls split with an argument other than the literal newline");
            }
            at = code.indexOf(".split(", at + 1);
        }
        return found;
    }

    @Test
    void the_extraction_code_uses_no_pattern_format_reflection_script_or_process_facility() throws IOException {
        Path root = Path.of("src/main/java");
        List<String> found = new ArrayList<>();
        int files = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                files++;
                found.addAll(violations(file.getFileName().toString(), Files.readString(file)));
            }
        }
        assertThat(files).as("the scan found the sources").isGreaterThanOrEqualTo(10);
        assertThat(found).isEmpty();
    }

    @Test
    void the_scanner_catches_invented_violations() {
        assertThat(violations("A.java", "Pattern.compile(userText);")).isNotEmpty();
        assertThat(violations("A.java", "import java.util.regex.Pattern;")).isNotEmpty();
        assertThat(violations("A.java", "s.matches(x);")).isNotEmpty();
        assertThat(violations("A.java", "s.replaceAll(a, b);")).isNotEmpty();
        assertThat(violations("A.java", "String.format(text, 1);")).isNotEmpty();
        assertThat(violations("A.java", "text.formatted(1);")).isNotEmpty();
        assertThat(violations("A.java", "Class.forName(name);")).isNotEmpty();
        assertThat(violations("A.java", "new ProcessBuilder(cmd);")).isNotEmpty();
        assertThat(violations("A.java", "s.split(separator);")).isNotEmpty();
        assertThat(violations("A.java", "s.split(\",\");")).isNotEmpty();
    }

    @Test
    void the_scanner_accepts_the_allowed_forms_and_ignores_comments() {
        assertThat(violations("A.java", "s.split(\"\\n\", -1);")).isEmpty();
        assertThat(violations("A.java", "// Pattern.compile is not used\n/* String.format( */ int x = 1;")).isEmpty();
    }
}
