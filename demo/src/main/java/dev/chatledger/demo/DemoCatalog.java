package dev.chatledger.demo;

import dev.chatledger.extraction.TextKeys;

import java.util.ArrayList;
import java.util.List;

/**
 * The demo shop's catalog: eight synthetic items, each with an English and a French name (the order parser looks a name up by its exact
 * key, so each language is its own row) and a fixed price in XAF (scale 0). Orders in the demo name items without a price, so their
 * totals come from here (tasks.md, task 9).
 */
public final class DemoCatalog {

    public record Item(String english, String french, long unitMinor) {
    }

    /** One row of {@code catalog_item}. */
    public record Row(String nameKey, String displayName, long unitMinor) {
    }

    public static final Item RICE = new Item("rice 5kg", "riz 5kg", 2500);
    public static final Item TOMATOES = new Item("tomato box", "boîte de tomates", 1500);
    public static final Item PALM_OIL = new Item("palm oil 1l", "huile de palme 1l", 1200);
    public static final Item PLANTAIN = new Item("plantain bunch", "régime de plantain", 3000);
    public static final Item SUGAR = new Item("sugar 1kg", "sucre 1kg", 800);
    public static final Item SOAP = new Item("soap bar", "savon", 350);
    public static final Item FLOUR = new Item("flour 2kg", "farine 2kg", 1400);
    public static final Item BEANS = new Item("beans 1kg", "haricots 1kg", 1100);

    public static final List<Item> ITEMS = List.of(RICE, TOMATOES, PALM_OIL, PLANTAIN, SUGAR, SOAP, FLOUR, BEANS);

    private DemoCatalog() {
    }

    /** Both names of every item, keyed as the parser keys a line's label. */
    public static List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        for (Item item : ITEMS) {
            rows.add(new Row(TextKeys.key(item.english()), item.english(), item.unitMinor()));
            rows.add(new Row(TextKeys.key(item.french()), item.french(), item.unitMinor()));
        }
        return List.copyOf(rows);
    }
}
