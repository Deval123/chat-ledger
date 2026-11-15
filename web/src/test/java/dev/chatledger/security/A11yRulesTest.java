package dev.chatledger.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The accessibility rules accept a page that meets them and refuse each invented fault (task 12a). No server, no database. */
class A11yRulesTest {

    /** A small page that meets every rule; each test below breaks one thing in it. */
    private static final String GOOD = """
            <!DOCTYPE html><html lang="en"><head><title>Board - chat-ledger</title></head><body>
            <a class="skip" href="#main">Skip to the main content</a>
            <header class="top"><nav aria-label="Main"><a href="/app">Board</a></nav>
              <p id="notice" role="status" aria-live="polite" class="notice"></p></header>
            <main id="main" tabindex="-1"><h1>Board</h1>
              <nav aria-label="Columns"><a href="#col-a">Awaiting 1</a></nav>
              <section id="col-a" aria-labelledby="h-a"><h2 id="h-a">Awaiting payment</h2>
                <article aria-labelledby="h-c1"><h3 id="h-c1">Ana</h3>
                  <form><button>Confirm<span class="sr">: Ana, 5,000 XAF</span></button></form></article>
                <article aria-labelledby="h-c2"><h3 id="h-c2">Bea</h3>
                  <form><button>Confirm<span class="sr">: Bea, 2,000 XAF</span></button></form></article>
              </section>
              <label for="t">Total</label><input id="t" name="t" aria-describedby="t-hint"><p id="t-hint">Hint</p>
              <table><tr><th scope="col">Check</th></tr></table>
              <p><a href="/app/columns/a">Show more<span class="sr">: Awaiting payment</span></a> <a href="/app">Board</a></p>
            </main></body></html>""";

    private static String without(String what, String with) {
        assertThat(GOOD).as("the control text exists").contains(what);
        return GOOD.replace(what, with);
    }

    @Test
    void a_page_that_meets_the_rules_passes() {
        assertThat(A11yRules.violations("good", GOOD)).isEmpty();
    }

    @Test
    void each_invented_fault_is_refused() {
        String[][] faults = {
                {"<html lang=\"en\">", "<html>"},
                {"<title>Board - chat-ledger</title>", "<title></title>"},
                {"<main id=\"main\" tabindex=\"-1\">", "<main>"},
                {"<a class=\"skip\" href=\"#main\">Skip to the main content</a>", ""},
                {"<h1>Board</h1>", "<h2>Board</h2>"},
                {"<h3 id=\"h-c1\">Ana</h3>", "<h4 id=\"h-c1\">Ana</h4>"},
                {"<label for=\"t\">Total</label>", ""},
                {"Confirm<span class=\"sr\">: Bea, 2,000 XAF</span>", "Confirm<span class=\"sr\">: Ana, 5,000 XAF</span>"},
                {"<a href=\"/app\">Board</a>", "<a href=\"/app\"></a>"},
                {"<p id=\"notice\" role=\"status\" aria-live=\"polite\" class=\"notice\"></p>", ""},
                {"class=\"notice\"></p>", "class=\"notice empty\"></p>"},
                {"<h1>Board</h1>", "<h1>Board</h1><p role=\"status\">Saved</p>"},
                {"<main id=\"main\" tabindex=\"-1\">", "<main id=\"main\" tabindex=\"2\">"},
                {"aria-describedby=\"t-hint\"", "aria-describedby=\"nowhere\""},
                {"<nav aria-label=\"Columns\">", "<nav>"},
                {"<th scope=\"col\">", "<th>"},
                {"<h1>Board</h1>", "<h1>Board</h1><img src=\"/a.png\">"},
                {"<h1>Board</h1>", "<h1>Board ??board.title_en??</h1>"},
                {"<a href=\"/app\">Board</a></p>", "<a href=\"/app\">Board</a> <a href=\"/app/columns/b\">Show more<span class=\"sr\">: Awaiting payment</span></a></p>"}};
        for (String[] fault : faults) {
            assertThat(A11yRules.violations("bad", without(fault[0], fault[1]))).as(fault[0] + " -> " + fault[1]).isNotEmpty();
        }
    }

    @Test
    void the_name_of_a_control_is_its_label_or_its_whole_text() {
        org.jsoup.nodes.Element button = org.jsoup.Jsoup.parse("<button>Confirm<span class=\"sr\">: Ana</span></button>").selectFirst("button");
        assertThat(A11yRules.name(button)).isEqualTo("Confirm: Ana");
        org.jsoup.nodes.Element labelled = org.jsoup.Jsoup.parse("<button aria-label=\"Close the notice\">x</button>").selectFirst("button");
        assertThat(A11yRules.name(labelled)).isEqualTo("Close the notice");
    }
}
