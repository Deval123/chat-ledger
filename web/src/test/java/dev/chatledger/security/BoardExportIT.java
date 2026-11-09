package dev.chatledger.security;

import dev.chatledger.extraction.HostileCorpus;
import dev.chatledger.orders.OrderIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CSV downloads: three files, a spreadsheet cannot be made to run a formula by a name in a message, phone numbers are masked, ranges
 * and rows are bounded, every download is audited and limited, and only a seller gets any of it. The output is read back with a CSV reader
 * written here, not the one in the code under test.
 *
 * <p>Requirements: 4.5, 9.1. Task 8.
 */
class BoardExportIT extends BoardIT {

    @Autowired
    OrderIngestionService orders;

    /** RFC 4180 reader: quoted cells with doubled quotes, CRLF or LF line ends. Returns rows of raw cell text and whether each was quoted. */
    record Cell(String text, boolean quoted) {
    }

    static List<List<Cell>> parse(String csv) {
        List<List<Cell>> rows = new ArrayList<>();
        List<Cell> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean inQuotes = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.length() == 0) {
                inQuotes = true;
                quoted = true;
            } else if (c == ',') {
                row.add(new Cell(cell.toString(), quoted));
                cell.setLength(0);
                quoted = false;
            } else if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
                row.add(new Cell(cell.toString(), quoted));
                rows.add(row);
                row = new ArrayList<>();
                cell.setLength(0);
                quoted = false;
                i++;
            } else {
                cell.append(c);
            }
        }
        assertThat(inQuotes).as("no quote left open").isFalse();
        assertThat(cell.length()).as("the file ends with a line end").isZero();
        return rows;
    }

    private static final List<String> FORMULAS = List.of("=cmd|' /C calc'!A0", "+1+1", "-2+3", "@SUM(1)", "|calc", "\t=1", "\r=1", "  =1",
            "\u00A0=1", "\uFF1D1+1", "\uFF0B1", "\uFF20SUM(1)");

    @Test
    void no_text_cell_can_be_read_as_a_formula_and_numbers_stay_numbers() {
        Workspace w = newWorkspace();
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        int n = 0;
        for (String formula : FORMULAS) {
            clock.advance(Duration.ofSeconds(3));
            // The name comes from the message, so a formula can be put there; the order may or may not parse, but it is stored either way.
            orders.ingest(w.tenantId(), w.sellerId(), "f-" + n, "Order: F-" + n + "\nName: " + formula + "\nPhone: +999 000 000 12" + (10 + n) + "\n1 x rice @ 1000");
            n++;
        }
        for (String name : HostileCorpus.names()) {
            clock.advance(Duration.ofSeconds(3));
            orders.ingest(w.tenantId(), w.sellerId(), "h-" + n++, HostileCorpus.get(name));
        }
        clock.advance(Duration.ofSeconds(3));
        orders.ingest(w.tenantId(), w.sellerId(), "neg", "Order: NEG-1\nName: Minus\n1 x rice @ 500");

        Browser.Response response = seller.get("/app/export/orders.csv");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("content-type")).isEqualToIgnoringCase("text/csv;charset=UTF-8");
        assertThat(response.header("content-disposition")).matches("attachment; filename=\"orders-\\d{4}-\\d{2}-\\d{2}\\.csv\"");
        assertThat(response.header("cache-control")).contains("no-store");
        assertThat(response.header("x-content-type-options")).isEqualTo("nosniff");
        String body = response.body();
        assertThat(body).startsWith("\uFEFF\"id\",\"recorded_at_utc\",\"status\"");
        List<List<Cell>> rows = parse(body.substring(1));
        assertThat(rows).hasSizeGreaterThan(40);
        int formulaRows = 0;
        for (List<Cell> row : rows.subList(1, rows.size())) {
            assertThat(row).as("every row has the columns of the header").hasSize(rows.get(0).size());
            for (int i = 0; i < row.size(); i++) {
                Cell cell = row.get(i);
                if (!cell.quoted()) {
                    // Unquoted cells are numbers the program wrote (total, applied, balance) or empty.
                    assertThat(cell.text()).as("unquoted cell in column " + rows.get(0).get(i).text()).matches("-?\\d*");
                    continue;
                }
                String text = cell.text();
                String trimmed = text.replaceFirst("^[\\s\\u00A0\\u2000-\\u200A\\u202F\\u205F\\u3000]+", "");
                if (!trimmed.isEmpty()) {
                    char first = trimmed.charAt(0);
                    assertThat("=+-@|\uFF1D\uFF0B\uFF0D\uFF20\t\r\n".indexOf(first))
                            .as("cell begins with a formula character: " + text.replace("\n", "\\n")).isEqualTo(-1);
                }
                if (text.startsWith("'")) {
                    formulaRows++;
                }
                assertThat(text).as("no control character in a cell").doesNotContainPattern("[\\p{Cntrl}&&[^ ]]");
            }
            assertThat(row.get(5).text()).as("the phone is masked").matches("|\\*{4}\\d{4}");
            for (int k = 6; k <= 8; k++) {
                assertThat(row.get(k).quoted()).as("total, applied and balance are numbers, not text").isFalse();
            }
        }
        assertThat(formulaRows).as("formula-like names were neutralised, not dropped").isGreaterThanOrEqualTo(3);
        // A number is not a text: the totals are digits only.
        List<String> totals = rows.subList(1, rows.size()).stream().map(r -> r.get(6).text()).filter(t -> !t.isEmpty()).toList();
        assertThat(totals).isNotEmpty().allMatch(t -> t.matches("\\d+"));
        // Full phone numbers never leave the application.
        assertThat(body).doesNotContain("000 000 12").doesNotContain("+999");
    }

    @Test
    void the_payments_and_ledger_files_have_their_columns_masked_phones_and_integer_amounts() {
        Workspace w = newWorkspace();
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        pasteOrder(seller, orderText("E-1", "Eve Ngu", "+999 000 000 1234", 5000));
        pastePayment(seller, sms(5000, "Eve Ngu", "+999 000 000 1234", "E-1"));

        List<List<Cell>> payments = parse(seller.get("/app/export/payments.csv").body().substring(1));
        assertThat(payments.get(0).stream().map(Cell::text)).containsExactly("id", "recorded_at_utc", "paid_at_utc", "status", "provider", "transaction_id",
                "sender", "phone", "reference", "amount", "currency", "matched_order_id");
        assertThat(payments).hasSize(2);
        List<Cell> payment = payments.get(1);
        assertThat(payment.get(3).text()).isEqualTo("MATCHED");
        assertThat(payment.get(7).text()).isEqualTo("****1234");
        assertThat(payment.get(9)).isEqualTo(new Cell("5000", false));
        assertThat(payment.get(11).text()).isEqualTo(orderId(w.tenantId(), "E-1").toString());

        List<List<Cell>> ledger = parse(seller.get("/app/export/ledger.csv").body().substring(1));
        assertThat(ledger.get(0).stream().map(Cell::text)).containsExactly("transaction_id", "created_at_utc", "kind", "reference_type", "reference_id",
                "account_code", "account_name", "debit", "credit", "currency");
        assertThat(ledger.subList(1, ledger.size()).stream().map(r -> r.get(2).text() + " " + r.get(5).text() + " " + r.get(7).text() + "/" + r.get(8).text()))
                .containsExactlyInAnyOrder("ORDER_ACCEPTED 1100 5000/0", "ORDER_ACCEPTED 4000 0/5000", "PAYMENT_MATCHED 1000 5000/0", "PAYMENT_MATCHED 1100 0/5000");
        long debits = ledger.subList(1, ledger.size()).stream().mapToLong(r -> Long.parseLong(r.get(7).text())).sum();
        long credits = ledger.subList(1, ledger.size()).stream().mapToLong(r -> Long.parseLong(r.get(8).text())).sum();
        assertThat(debits).isEqualTo(credits);

        // Every download leaves an audit row with the file and the row count, and nothing from a message.
        assertThat(rows("SELECT subject FROM audit_log WHERE tenant_id = ? AND action = 'EXPORT_CSV' ORDER BY subject", w.tenantId()))
                .containsExactly("ledger rows=4", "payments rows=1");
        assertThat(one("SELECT count(*)::text FROM audit_log WHERE tenant_id = ? AND action = 'EXPORT_CSV' AND actor = ?", w.tenantId(), w.sellerId())).isEqualTo("2");
    }

    @Test
    void dates_are_bounded_and_a_helper_gets_nothing_and_leaves_no_audit_row() {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        Browser helperBrowser = signedIn(w.slug(), helper.email(), helper.password());
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        assertThat(seller.get("/app/export/orders.csv?from=" + today.minusDays(365) + "&to=" + today).status()).as("366 days").isEqualTo(200);
        for (String bad : List.of("from=" + today.minusDays(366) + "&to=" + today, "from=" + today + "&to=" + today.minusDays(1), "from=garbage",
                "to=2000-13-45", "from=" + today.plusDays(1) + "&to=" + today.plusDays(2), "from=1999-01-01&to=1999-01-02", "to=" + today.plusDays(1),
                "from=2026-1-1", "from=%00")) {
            assertThat(seller.get("/app/export/orders.csv?" + bad).status()).as(bad).isEqualTo(400);
        }
        for (String file : List.of("orders", "payments", "ledger")) {
            Browser.Response r = helperBrowser.get("/app/export/" + file + ".csv");
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.header("content-type")).doesNotContain("csv");
        }
        assertThat(one("SELECT count(*)::text FROM audit_log WHERE tenant_id = ? AND action = 'EXPORT_CSV'", w.tenantId())).isEqualTo("1");
        assertThat(seller.get("/app/export").body()).contains("Download your records").contains("apostrophe");
    }

    @Test
    void the_seventh_download_in_ten_minutes_is_refused_with_429_and_each_workspace_has_its_own_allowance() {
        Workspace w = newWorkspace();
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        for (int i = 0; i < 6; i++) {
            assertThat(seller.get("/app/export/orders.csv").status()).as("download " + i).isEqualTo(200);
        }
        Browser.Response refused = seller.get("/app/export/payments.csv");
        assertThat(refused.status()).isEqualTo(429);
        assertThat(refused.header("retry-after")).matches("\\d+");
        assertThat(refused.header("content-type")).doesNotContain("csv");
        Workspace other = newWorkspace();
        assertThat(signedIn(other.slug(), other.email(), other.password()).get("/app/export/orders.csv").status()).isEqualTo(200);
        clock.advance(Duration.ofMinutes(11));
        assertThat(seller.get("/app/export/payments.csv").status()).isEqualTo(200);
        assertThat(Map.of("rows", rows("SELECT subject FROM audit_log WHERE tenant_id = ? AND action = 'EXPORT_CSV'", w.tenantId()).size())).containsEntry("rows", 7);
    }
}
