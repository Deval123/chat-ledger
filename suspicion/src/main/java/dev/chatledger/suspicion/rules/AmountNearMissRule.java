package dev.chatledger.suspicion.rules;

import dev.chatledger.suspicion.Finding;
import dev.chatledger.suspicion.FlagCode;
import dev.chatledger.suspicion.PairContext;
import dev.chatledger.suspicion.Rule;
import dev.chatledger.suspicion.SuspicionConfig;

import java.util.Optional;

/**
 * R3. The amount is the order's balance with one digit missing ({@code 50000} paid as {@code 5000}) or with two neighbouring digits swapped
 * ({@code 4500} paid as {@code 4050}). A substituted digit is not a slip but an ordinary part payment ({@code 3000} of {@code 5000}), and an
 * added digit is an overpayment (R11), so neither counts. Balances below {@code near-miss-min} are ignored. Digits are compared as text of at
 * most 13 characters (the amounts are at most 10^12).
 */
public final class AmountNearMissRule implements Rule<PairContext> {
    @Override
    public FlagCode code() {
        return FlagCode.AMOUNT_NEAR_MISS;
    }

    @Override
    public Optional<Finding> evaluate(PairContext pair, SuspicionConfig config) {
        long balance = pair.order().balanceMinor();
        long paid = pair.payment().amountMinor();
        if (balance < config.nearMissMinMinor() || paid == balance) {
            return Optional.empty();
        }
        String b = Long.toString(balance);
        String p = Long.toString(paid);
        return oneDigitMissing(b, p) || neighboursSwapped(b, p)
                ? Optional.of(Finding.pair(code(), pair.payment().id(), pair.order().id())) : Optional.empty();
    }

    static boolean oneDigitMissing(String balance, String paid) {
        for (int i = 0; i < balance.length(); i++) {
            if (stripZeros(balance.substring(0, i) + balance.substring(i + 1)).equals(paid)) {
                return true;
            }
        }
        return false;
    }

    static boolean neighboursSwapped(String balance, String paid) {
        for (int i = 0; i + 1 < balance.length(); i++) {
            char[] chars = balance.toCharArray();
            chars[i] = balance.charAt(i + 1);
            chars[i + 1] = balance.charAt(i);
            if (stripZeros(new String(chars)).equals(paid)) {
                return true;
            }
        }
        return false;
    }

    private static String stripZeros(String digits) {
        int i = 0;
        while (i < digits.length() - 1 && digits.charAt(i) == '0') {
            i++;
        }
        return digits.substring(i);
    }
}
