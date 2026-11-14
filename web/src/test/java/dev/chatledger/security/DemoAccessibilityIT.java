package dev.chatledger.security;

import dev.chatledger.demo.ops.DemoWorld;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pages a judge sees first, on the demo deployment, meet the structural accessibility rules ({@link A11yRules}): the entry page,
 * the board of a seeded workspace, the scenarios, the summary and the books. The scenario buttons name their scenario, and the chat
 * says who speaks without relying on the side or the tint of a bubble.
 *
 * <p>Task 12a.
 */
class DemoAccessibilityIT extends AbstractDemoWebIT {

    @Test
    void the_demo_flow_meets_the_rules() {
        DemoWorld.retireEverythingWaiting();
        DemoWorld.seeded();
        List<String> found = new ArrayList<>(A11yRules.violations("/demo", browser().get("/demo").body()));
        Browser visitor = entered();
        for (String path : List.of("/app", "/app/demo", "/app/summary", "/app/books", "/app/columns/flagged")) {
            Browser.Response page = visitor.get(path);
            assertThat(page.status()).as(path).isEqualTo(200);
            found.addAll(A11yRules.violations("demo " + path, page.body()));
        }
        assertThat(found).isEmpty();
    }

    @Test
    void each_scenario_button_names_its_scenario_and_the_chat_names_who_speaks() {
        DemoWorld.retireEverythingWaiting();
        DemoWorld.seeded();
        Document page = Jsoup.parse(entered().get("/app/demo").body());
        List<String> names = page.select("section.scenario button").stream().map(A11yRules::name).toList();
        assertThat(names).hasSize(21).doesNotHaveDuplicates().allMatch(n -> n.matches("(Send the order|Send the payment|Run both): .+"));
        assertThat(page.select(".bubble.from > .sr").eachText()).isNotEmpty().allMatch("Customer:"::equals);
        assertThat(page.select(".bubble.to > .sr").eachText()).isNotEmpty().allMatch("You:"::equals);
        assertThat(page.select("section.chat > h3, section.feed > h3")).hasSize(14);
    }
}
