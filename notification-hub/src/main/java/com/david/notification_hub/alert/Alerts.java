package com.david.notification_hub.alert;

import com.david.notification_hub.event.StoredEvent;
import com.david.notification_hub.source.EventStatus;
import com.david.notification_hub.source.SourceType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Turns what happened to an event into a Discord message and an outbox key.
 *
 * The key decides what counts as a repeat. The outbox holds one row per key, user and
 * channel, so a re-poll, a retried transaction or a second instance cannot double-send:
 *
 * <pre>
 *   &lt;id&gt;:new              the first time this user hears of the event
 *   &lt;id&gt;:r&lt;revision&gt;      a content change; each change has its own revision, so a
 *                         price going 49 -> 39 -> 49 -> 39 alerts every time
 *   &lt;id&gt;:soon:&lt;epoch&gt;     starting soon, for that start time - a rescheduled event is
 *                         reminded again
 *   &lt;id&gt;:onsale:&lt;epoch&gt;   on sale soon, likewise
 * </pre>
 *
 * Times use Discord's {@code <t:epoch:F>} markup, which each reader sees in their own
 * time zone. Text from a source is untrusted and goes through {@link #safe}.
 */
public final class Alerts {

    private Alerts() {}

    public static Alert newEvent(StoredEvent e, String discordUserId) {
        boolean deal = isDeal(e);
        List<String> lines = new ArrayList<>();
        lines.add(mention(discordUserId));
        if (e.getStatus() != EventStatus.SCHEDULED) lines.add("**Status:** " + word(e.getStatus()));
        if (!deal) lines.add("**When:** " + whenAndHowSoon(e.getStartsAt()));
        if (e.getVenue() != null) lines.add("**Where:** " + safe(e.getVenue()));
        if (e.getPrice() != null) lines.add((deal ? "**Price:** " : "**From:** ") + money(e.getPrice()));
        if (e.getOnSaleAt() != null) lines.add("**On sale:** " + when(e.getOnSaleAt()));

        String headline = deal ? "Deal" : "New";
        return alert(e, e.getExternalId() + ":new", headline + ": " + safe(e.getTitle()), lines);
    }

    /** Lists only what changed, each as before -> after. */
    public static Alert updated(StoredEvent now, EventSnapshot before, String discordUserId) {
        List<String> lines = new ArrayList<>();
        lines.add(mention(discordUserId));
        if (before.status() != now.getStatus()) {
            lines.add("**Status:** " + word(before.status()) + " → " + word(now.getStatus()));
        }
        if (!sameInstant(before.startsAt(), now.getStartsAt())) {
            lines.add("**When:** " + when(before.startsAt()) + " → " + when(now.getStartsAt()));
        }
        if (!Objects.equals(before.venue(), now.getVenue())) {
            lines.add("**Where:** " + place(before.venue()) + " → " + place(now.getVenue()));
        }
        if (!samePrice(before.price(), now.getPrice())) {
            lines.add("**Price:** " + money(before.price()) + " → " + money(now.getPrice()));
        }
        if (!sameInstant(before.onSaleAt(), now.getOnSaleAt())) {
            lines.add("**On sale:** " + when(before.onSaleAt()) + " → " + when(now.getOnSaleAt()));
        }

        return alert(now, now.getExternalId() + ":r" + now.getRevision(),
                headline(before, now) + ": " + safe(now.getTitle()), lines);
    }

    public static Alert startingSoon(StoredEvent e, String discordUserId) {
        OffsetDateTime startsAt = Objects.requireNonNull(e.getStartsAt(), "startsAt");
        List<String> lines = new ArrayList<>();
        lines.add(mention(discordUserId));
        lines.add("**When:** " + whenAndHowSoon(startsAt));
        if (e.getVenue() != null) lines.add("**Where:** " + safe(e.getVenue()));

        return alert(e, e.getExternalId() + ":soon:" + startsAt.toEpochSecond(),
                "Starting soon: " + safe(e.getTitle()), lines);
    }

    public static Alert onSaleSoon(StoredEvent e, String discordUserId) {
        OffsetDateTime onSaleAt = Objects.requireNonNull(e.getOnSaleAt(), "onSaleAt");
        List<String> lines = new ArrayList<>();
        lines.add(mention(discordUserId));
        lines.add("**On sale:** " + whenAndHowSoon(onSaleAt));
        if (e.getStartsAt() != null) lines.add("**When:** " + when(e.getStartsAt()));
        if (e.getVenue() != null) lines.add("**Where:** " + safe(e.getVenue()));

        return alert(e, e.getExternalId() + ":onsale:" + onSaleAt.toEpochSecond(),
                "On sale soon: " + safe(e.getTitle()), lines);
    }

    /** The outbox's external_source for a source's alerts, e.g. "ticketmaster". */
    public static String outboxSource(SourceType source) {
        return source.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Makes text from a source show as written and never act as Discord markup. Without
     * this an event named "@everyone" would notify a whole server - the webhook sends
     * content with mentions enabled - and "[Free](https://...)" would render as a link
     * pointing anywhere.
     */
    static String safe(String untrusted) {
        StringBuilder sb = new StringBuilder(untrusted.length());
        for (char c : untrusted.toCharArray()) {
            switch (c) {
                case '\\', '*', '_', '~', '`', '|', '[' -> sb.append('\\').append(c);
                // A zero-width space after the @ leaves it readable but inert
                case '@' -> sb.append("@​");
                // Kept on the line it was put on, after a label, so it can never start a
                // line and become a heading, quote or list
                case '\n', '\r' -> sb.append(' ');
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---------- helpers ----------

    private static Alert alert(StoredEvent e, String key, String title, List<String> lines) {
        List<String> all = new ArrayList<>(lines);
        // Left as-is: escaping would break a url, and it comes from the API, not a user
        if (e.getUrl() != null) all.add(e.getUrl());
        return new Alert(outboxSource(e.getSource()), key, title, String.join("\n", all));
    }

    private static String headline(EventSnapshot before, StoredEvent now) {
        if (before.status() != now.getStatus() && now.getStatus() != EventStatus.SCHEDULED) {
            return word(now.getStatus());
        }
        if (before.price() != null && now.getPrice() != null && now.getPrice().compareTo(before.price()) < 0) {
            return "Price drop";
        }
        return "Updated";
    }

    private static boolean isDeal(StoredEvent e) {
        return e.getSource() == SourceType.CHEAPSHARK;
    }

    private static String mention(String discordUserId) {
        return "<@" + discordUserId + ">";
    }

    private static String when(OffsetDateTime t) {
        return t == null ? "TBA" : "<t:" + t.toEpochSecond() + ":F>";
    }

    // "Friday, 31 December 2026 20:00 (in 2 months)"
    private static String whenAndHowSoon(OffsetDateTime t) {
        return t == null ? "TBA" : when(t) + " (<t:" + t.toEpochSecond() + ":R>)";
    }

    private static String place(String venue) {
        return venue == null ? "none" : safe(venue);
    }

    private static String money(BigDecimal price) {
        return price == null ? "none listed" : "$" + price.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    // "CANCELED" -> "Canceled"
    private static String word(EventStatus status) {
        String name = status.name();
        return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
    }

    private static boolean sameInstant(OffsetDateTime a, OffsetDateTime b) {
        return a == null ? b == null : b != null && a.isEqual(b);
    }

    private static boolean samePrice(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }
}
