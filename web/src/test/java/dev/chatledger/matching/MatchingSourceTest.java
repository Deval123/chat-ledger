package dev.chatledger.matching;

import dev.chatledger.orders.OrderRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Facts about the matching code that no database is needed to check: scoring cannot read the text of a message, the code uses no pattern or
 * format facility, the account codes agree with the database trigger, and the neutral customer name is the same in both modules.
 *
 * <p>Requirement: 3, 9.2. Task 6.1.
 */
class MatchingSourceTest {

    private static final Path MAIN = Path.of("src/main/java/dev/chatledger/matching");
    private static final Path MIGRATION = Path.of("src/main/resources/db/migration/V016__matching.sql");

    private static String code(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> walk = Files.list(MAIN)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    /** What a source file mentions that scoring must never touch. */
    static List<String> violations(String file, String source) {
        String code = code(source);
        List<String> found = new ArrayList<>();
        for (String bad : List.of("raw_text", "rawText", "java.util.regex", "Pattern.", "Matcher", ".matches(", ".replaceAll(", "String.format(",
                ".formatted(", "Class.forName", "ProcessBuilder", "ScriptEngine")) {
            if (code.contains(bad)) {
                found.add(file + " mentions " + bad);
            }
        }
        return found;
    }

    @Test
    void the_matching_code_never_selects_the_text_of_a_message_and_uses_no_pattern_or_format_facility() throws IOException {
        List<Path> files = sources();
        assertThat(files).as("the scan found the sources").hasSizeGreaterThanOrEqualTo(4);
        List<String> found = new ArrayList<>();
        for (Path file : files) {
            found.addAll(violations(file.getFileName().toString(), Files.readString(file)));
        }
        assertThat(found).isEmpty();
    }

    @Test
    void the_scan_catches_invented_violations_and_ignores_comments() {
        assertThat(violations("A.java", "ps.prepareStatement(\"SELECT raw_text FROM orders\");")).isNotEmpty();
        assertThat(violations("A.java", "String s = order.rawText();")).isNotEmpty();
        assertThat(violations("A.java", "Pattern.compile(x);")).isNotEmpty();
        assertThat(violations("A.java", "name.matches(x);")).isNotEmpty();
        assertThat(violations("A.java", "String.format(a, b);")).isNotEmpty();
        assertThat(violations("A.java", "// raw_text is never read\n/* Pattern. */ int x = 1;")).isEmpty();
    }

    @Test
    void the_account_codes_are_the_ones_the_database_trigger_checks() throws IOException {
        String migration = Files.readString(MIGRATION);
        assertThat(migration).contains("a.code = '" + ChartOfAccounts.CLEARING_CODE + "' THEN e.debit_minor")
                .contains("a.code = '" + ChartOfAccounts.RECEIVABLE_CODE + "' THEN e.credit_minor")
                .contains("a.code = '" + ChartOfAccounts.CUSTOMER_CREDIT_CODE + "' THEN e.credit_minor");
    }

    @Test
    void the_neutral_customer_name_is_the_one_the_order_service_stores() {
        assertThat(NameSimilarity.NEUTRAL_NAME).isEqualTo(OrderRepository.UNKNOWN_CUSTOMER);
    }

    @Test
    void the_reasons_character_rule_of_the_database_is_the_one_the_reason_codes_fit() throws IOException {
        assertThat(Files.readString(MIGRATION)).contains("reasons_json ~ '^[][A-Za-z0-9_:,\"{} ]*$'");
    }
}
