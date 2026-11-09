package dev.chatledger.suspicion;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The suspicion rules are pure: no regular expression, format string, reflection, script or process (as in {@code matching}), and no clock,
 * database or environment. A rule gets the time it compares with from its context, so it cannot read the clock behind the caller's back.
 *
 * <p>Requirement: 5, 9.2. Task 7.
 */
class NoInputPatternsTest {
    private static final List<String> FORBIDDEN = List.of(
            "java.util.regex", "Pattern.", "Matcher", ".matches(", ".replaceAll(", ".replaceFirst(", "String.format(", ".formatted(",
            "Class.forName", "getMethod(", "getDeclaredMethod(", "ScriptEngine", "ObjectInputStream", "Runtime.getRuntime",
            "ProcessBuilder", "MethodHandles", "System.getenv", "System.getProperty", "URLClassLoader", "Expression", "XMLDecoder",
            ".split(", "Instant.now", "Clock", "LocalDate.now", "System.currentTimeMillis", "java.sql", "javax.sql", "java.net", "java.io",
            "Thread", "Random");

    static List<String> violations(String file, String source) {
        String code = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        List<String> found = new ArrayList<>();
        for (String bad : FORBIDDEN) {
            if (code.contains(bad)) {
                found.add(file + " uses " + bad);
            }
        }
        return found;
    }

    @Test
    void the_suspicion_code_uses_no_pattern_clock_database_or_reflection() throws IOException {
        List<String> found = new ArrayList<>();
        int files = 0;
        try (Stream<Path> walk = Files.walk(Path.of("src/main/java"))) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                files++;
                found.addAll(violations(file.getFileName().toString(), Files.readString(file)));
            }
        }
        assertThat(files).as("the scan found the sources").isGreaterThanOrEqualTo(20);
        assertThat(found).isEmpty();
    }

    @Test
    void the_scanner_catches_invented_violations_and_ignores_comments() {
        for (String bad : List.of("Pattern.compile(x);", "s.matches(x);", "String.format(x);", "Instant.now();", "java.sql.Connection c;",
                "Clock.systemUTC();", "new Random();", "s.split(\",\");")) {
            assertThat(violations("A.java", bad)).as(bad).isNotEmpty();
        }
        assertThat(violations("A.java", "// Instant.now is not used\n/* Pattern. */ int x = 1;")).isEmpty();
    }
}
