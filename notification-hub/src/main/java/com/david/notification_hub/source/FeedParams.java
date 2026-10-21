package com.david.notification_hub.source;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Reads a feed's params for a source. A bad value throws IllegalArgumentException
 * naming the param, so a broken feed explains itself in the poller's log.
 */
public final class FeedParams {

    private FeedParams() {}

    public static String text(Map<String, String> params, String key) {
        String value = optionalText(params, key);
        if (value == null) {
            throw new IllegalArgumentException("feed param '" + key + "' is missing");
        }
        return value;
    }

    /** @return the value stripped, or null when absent or blank */
    public static String optionalText(Map<String, String> params, String key) {
        String value = params == null ? null : params.get(key);
        return (value == null || value.isBlank()) ? null : value.strip();
    }

    public static BigDecimal number(Map<String, String> params, String key) {
        String value = text(params, key);
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("feed param '" + key + "' is not a number: " + value);
        }
    }

    /** As {@link #number}, and also within [min, max]. */
    public static BigDecimal number(Map<String, String> params, String key, double min, double max) {
        BigDecimal value = number(params, key);
        if (value.doubleValue() < min || value.doubleValue() > max) {
            throw new IllegalArgumentException(
                    "feed param '" + key + "' must be between " + min + " and " + max + ": " + value);
        }
        return value;
    }
}
