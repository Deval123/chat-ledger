package dev.chatledger.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A range with more rows than the cap is refused, never cut: the user is sent back to the download page with a sentence, nothing is sent,
 * and no audit row is written. The cap is lowered here (the setting can only lower it) so the test needs three rows and not a hundred thousand.
 *
 * <p>Requirement: 4.5. Task 8.
 */
@TestPropertySource(properties = {"app.security.limits.actions-per-user=2/1m", "app.export.max-rows=3"})   // shares its context with BoardActionLimitIT
class BoardExportCapIT extends BoardIT {

    @Test
    void a_file_over_the_cap_is_refused_whole_and_exactly_the_cap_is_allowed() {
        Workspace w = newWorkspace();
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofSeconds(31));   // the shared context also limits actions to 2 a minute
            pasteOrder(seller, orderText("CAP-" + i, "Cap Customer", "+999 000 000 1234", 100 + i));
        }
        Browser.Response exact = seller.get("/app/export/orders.csv");
        assertThat(exact.status()).isEqualTo(200);
        assertThat(exact.body().split("\r\n")).hasSize(4);
        clock.advance(Duration.ofSeconds(31));   // the shared context also limits actions to 2 a minute
        pasteOrder(seller, orderText("CAP-3", "Cap Customer", "+999 000 000 1234", 103));
        Browser.Response over = seller.get("/app/export/orders.csv");
        assertThat(over.status()).isEqualTo(302);
        assertThat(path(over)).isEqualTo("/app/export?n=EXPORT_TOO_LARGE");
        assertThat(over.body()).doesNotContain("Cap Customer");
        assertThat(seller.get(path(over)).body()).contains("Too many rows for one file. Narrow the dates.");
        assertThat(rows("SELECT subject FROM audit_log WHERE tenant_id = ? AND action = 'EXPORT_CSV'", w.tenantId())).containsExactly("orders rows=3");
    }
}
