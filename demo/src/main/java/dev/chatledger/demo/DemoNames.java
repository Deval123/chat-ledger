package dev.chatledger.demo;

import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The only people and phone numbers the demo ever writes: obviously fictional names, and numbers in the synthetic range {@code +999 000}
 * (the repository's convention; a test fails on any other name or number in this module). Product rule: no real names, no real numbers.
 *
 * <p>Three groups whose first letters and surnames are all different, so two names of different people never agree by name or by initial
 * (which would add identity points the scenarios do not expect): the seed's customers, the scenarios' customers, and strangers (a sender
 * unlike the customer, rule R8).
 */
public final class DemoNames {

    /** The seed's customers, in seed order. */
    public static final List<String> SEED = List.of(
            "Ana Testa", "Bea Sample", "Cy Example", "Dee Demo", "Eve Mock", "Fay Fiction", "Gus Placeholder", "Hal Madeup",
            "Ivy Pretend", "Jo Dummy");

    /** One customer per one-click scenario, so two scenarios' orders never compete for the same payment by name. */
    public static final List<String> SCENARIO = List.of(
            "Max Mockup", "Nia Notreal", "Oto Sampled", "Pia Fictive", "Quin Testcase", "Rae Imaginary", "Sol Invented");

    /** Senders unlike any customer. */
    public static final List<String> STRANGERS = List.of("Kim Unknown", "Lee Nobody");

    private static final Set<String> ALL = Stream.of(SEED, SCENARIO, STRANGERS).flatMap(List::stream).collect(Collectors.toUnmodifiableSet());

    /** The synthetic range: every demo phone starts with this, and a demo workspace refuses any other number. */
    public static final String PHONE_PREFIX = "+999 000 ";

    private DemoNames() {
    }

    public static boolean allowed(String name) {
        return ALL.contains(name);
    }

    /** A fixed number for the seed: {@code +999 000 000 1NNN}. */
    public static String seedPhone(int n) {
        if (n < 0 || n > 999) {
            throw new IllegalArgumentException("seed phone number out of range");
        }
        return PHONE_PREFIX + "000 1" + String.format("%03d", n);
    }

    /**
     * A fresh number for one click, {@code +999 000 0NN NNNN} with six random digits, so two clicks almost never share a phone (a shared
     * phone under two names would rightly raise R10, which a scenario does not expect). The seed's numbers ({@code 000 1NNN}) are never
     * drawn.
     */
    public static String freshPhone(RandomGenerator random) {
        int six = 100_000 + random.nextInt(900_000);   // 100000..999999: the first pair is never 00, so never a seed number
        String digits = Integer.toString(six);
        return PHONE_PREFIX + "0" + digits.substring(0, 2) + " " + digits.substring(2);
    }

    /** True for a number written in the synthetic range, as the demo writes them. */
    public static boolean syntheticPhone(String phone) {
        return phone != null && phone.startsWith(PHONE_PREFIX);
    }
}
