package dev.chatledger.orders;

/** A tenant's currency and its number of decimal places, read from the tenant's own row. */
public record TenantMoney(String currency, int scale) {
}
