package dev.chatledger.board;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A spreadsheet opens a CSV and runs a cell that looks like a formula. Every text cell that came from a message is neutralised; numbers the
 * program wrote are not. Task 8 (formula injection).
 */
class CsvWriterTest {

    @Test
    void each_formula_starting_character_gets_a_quote_prefix() {
        for (String start : new String[]{"=", "+", "-", "@", "|", "\uFF1D", "\uFF0B", "\uFF0D", "\uFF20"}) {
            assertThat(CsvWriter.text(start + "1+1")).as("starts with U+%04X", (int) start.charAt(0)).isEqualTo("\"'" + start + "1+1\"");
        }
    }

    @Test
    void a_cell_that_starts_with_a_tab_carriage_return_or_line_feed_is_neutralised_even_when_a_formula_follows() {
        assertThat(CsvWriter.text("\t=1")).isEqualTo("\"' =1\"");
        assertThat(CsvWriter.text("\r=1")).isEqualTo("\"' =1\"");
        assertThat(CsvWriter.text("\n=1")).isEqualTo("\"' =1\"");
        assertThat(CsvWriter.text("\tplain")).as("a tab start alone is enough").isEqualTo("\"' plain\"");
        assertThat(CsvWriter.text("\rplain")).as("a carriage return start alone is enough").isEqualTo("\"' plain\"");
        assertThat(CsvWriter.text("\nplain")).as("a line feed start alone is enough").isEqualTo("\"' plain\"");
    }

    @Test
    void leading_spaces_do_not_hide_a_formula() {
        assertThat(CsvWriter.text("  =1")).isEqualTo("\"'  =1\"");
        assertThat(CsvWriter.text("\u00A0=1")).as("no-break space").isEqualTo("\"'\u00A0=1\"");
        assertThat(CsvWriter.text(" \t -2+3")).isEqualTo("\"'   -2+3\"");
    }

    @Test
    void the_classic_payloads_are_text_when_a_spreadsheet_reads_them() {
        for (String payload : new String[]{"=cmd|' /C calc'!A0", "+1+1", "-2+3", "@SUM(1+1)", "=HYPERLINK(\"http://x\",\"y\")", "|calc", "=1+1\r\n=2+2"}) {
            String cell = CsvWriter.text(payload);
            assertThat(cell).startsWith("\"'");
            assertThat(cell).doesNotContain("\r").doesNotContain("\n");
        }
    }

    @Test
    void ordinary_text_is_quoted_and_not_prefixed() {
        assertThat(CsvWriter.text("Ana Kamga")).isEqualTo("\"Ana Kamga\"");
        assertThat(CsvWriter.text("A-1042")).isEqualTo("\"A-1042\"");
        assertThat(CsvWriter.text("5 x rice")).isEqualTo("\"5 x rice\"");
        assertThat(CsvWriter.text("a=b+c")).as("only the first character matters").isEqualTo("\"a=b+c\"");
        assertThat(CsvWriter.text(null)).isEqualTo("\"\"");
        assertThat(CsvWriter.text("")).isEqualTo("\"\"");
    }

    @Test
    void quotes_are_doubled_and_line_breaks_inside_a_cell_become_spaces() {
        assertThat(CsvWriter.text("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CsvWriter.text("two\r\nlines\nhere")).isEqualTo("\"two  lines here\"");
        assertThat(CsvWriter.text("\"=1\"")).as("a quote first is not a formula").isEqualTo("\"\"\"=1\"\"\"");
    }

    @Test
    void invisible_and_control_characters_are_removed_and_do_not_hide_a_formula() {
        assertThat(CsvWriter.text("\u202E=1")).as("a bidirectional override in front").isEqualTo("\"'=1\"");
        assertThat(CsvWriter.text("\u200B=1")).isEqualTo("\"'=1\"");
        assertThat(CsvWriter.text("a\u0000b")).isEqualTo("\"ab\"");
    }

    @Test
    void numbers_written_by_the_program_are_never_prefixed() {
        assertThat(CsvWriter.number(-500, 0)).isEqualTo("-500");
        assertThat(CsvWriter.number(5025, 2)).isEqualTo("50.25");
        assertThat(CsvWriter.integer(-3)).isEqualTo("-3");
        assertThat(CsvWriter.text("-500")).as("but the same characters from a message are text").isEqualTo("\"'-500\"");
    }

    @Test
    void a_line_ends_with_crlf() {
        assertThat(CsvWriter.line(List.of("a", "b"))).isEqualTo("a,b\r\n");
    }
}
