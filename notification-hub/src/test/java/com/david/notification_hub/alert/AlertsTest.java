package com.david.notification_hub.alert;

import com.david.notification_hub.event.StoredEvent;
import com.david.notification_hub.feed.Feed;
import com.david.notification_hub.source.Event;
import com.david.notification_hub.source.EventStatus;
import com.david.notification_hub.source.SourceType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The exact text people will read, and the keys that decide what counts as a repeat. */
class AlertsTest {

    private static final OffsetDateTime STARTS = OffsetDateTime.parse("2026-12-31T20:00:00Z");
    private static final OffsetDateTime ON_SALE = OffsetDateTime.parse("2026-11-01T17:00:00Z");
    private static final String ALICE = "111111111111111111";

    // ---------- new ----------

    @Test
    void aNewConcertMentionsTheUserAndSaysWhatIsKnown() {
        Alert a = Alerts.newEvent(concert(), ALICE);

        assertEquals("ticketmaster", a.externalSource());
        assertEquals("tm-1:new", a.externalId());
        assertEquals("New: Band Live", a.title());
        assertEquals(String.join("\n",
                "<@" + ALICE + ">",
                "**When:** " + full(STARTS) + " (<t:" + STARTS.toEpochSecond() + ":R>)",
                "**Where:** The Fillmore, San Francisco",
                "**From:** $49.50",
                "**On sale:** " + full(ON_SALE),
                "https://www.ticketmaster.com/event/tm-1"), a.body());
    }

    @Test
    void aNewDealHasAPriceAndNoDate() {
        Alert a = Alerts.newEvent(deal(), ALICE);

        assertEquals("cheapshark", a.externalSource());
        assertEquals("200:new", a.externalId());
        assertEquals("Deal: Hades", a.title());
        assertEquals(String.join("\n",
                "<@" + ALICE + ">",
                "**Price:** $9.99",
                "https://www.cheapshark.com/redirect?dealID=abc%3D"), a.body());
    }

    @Test
    void anUnknownDateIsTBAAndMissingDetailsAreLeftOut() {
        StoredEvent e = concert();
        e.setStartsAt(null);
        e.setOnSaleAt(null);
        e.setVenue(null);
        e.setPrice(null);

        assertEquals(String.join("\n",
                "<@" + ALICE + ">",
                "**When:** TBA",
                "https://www.ticketmaster.com/event/tm-1"), Alerts.newEvent(e, ALICE).body());
    }

    @Test
    void aNewEventThatIsAlreadyPostponedSaysSo() {
        StoredEvent e = concert();
        e.setStatus(EventStatus.POSTPONED);

        assertTrue(Alerts.newEvent(e, ALICE).body().contains("**Status:** Postponed"));
    }

    // ---------- updates ----------

    @Test
    void anUpdateListsOnlyWhatChanged() {
        StoredEvent e = concert();
        EventSnapshot before = EventSnapshot.of(e);
        e.setPrice(new BigDecimal("39.50"));
        e.setRevision(1);

        Alert a = Alerts.updated(e, before, ALICE);

        assertEquals("tm-1:r1", a.externalId());
        assertEquals("Price drop: Band Live", a.title());
        assertEquals(String.join("\n",
                "<@" + ALICE + ">",
                "**Price:** $49.50 → $39.50",
                "https://www.ticketmaster.com/event/tm-1"), a.body());
    }

    @Test
    void aCancellationLeadsWithIt() {
        StoredEvent e = concert();
        EventSnapshot before = EventSnapshot.of(e);
        e.setStatus(EventStatus.CANCELED);

        Alert a = Alerts.updated(e, before, ALICE);

        assertEquals("Canceled: Band Live", a.title());
        assertTrue(a.body().contains("**Status:** Scheduled → Canceled"));
    }

    @Test
    void otherChangesAreUpdatesShowingBeforeAndAfter() {
        StoredEvent e = concert();
        EventSnapshot before = EventSnapshot.of(e);
        OffsetDateTime moved = STARTS.plusDays(1);
        e.setStartsAt(moved);
        e.setVenue(null);
        e.setPrice(new BigDecimal("55.00"));

        Alert a = Alerts.updated(e, before, ALICE);

        assertEquals("Updated: Band Live", a.title(), "a price rise is not a price drop");
        assertTrue(a.body().contains("**When:** " + full(STARTS) + " → " + full(moved)));
        assertTrue(a.body().contains("**Where:** The Fillmore, San Francisco → none"));
        assertTrue(a.body().contains("**Price:** $49.50 → $55.00"));
        assertFalse(a.body().contains("On sale"), "unchanged fields are left out");
    }

    @Test
    void aPriceDisappearingIsAnUpdateNotADrop() {
        StoredEvent e = concert();
        EventSnapshot before = EventSnapshot.of(e);
        e.setPrice(null);

        Alert a = Alerts.updated(e, before, ALICE);

        assertEquals("Updated: Band Live", a.title());
        assertTrue(a.body().contains("**Price:** $49.50 → none listed"));
    }

    // ---------- reminders ----------

    @Test
    void remindersAreKeyedByTheMomentTheyAreAbout() {
        StoredEvent e = concert();
        Alert soon = Alerts.startingSoon(e, ALICE);
        Alert onSale = Alerts.onSaleSoon(e, ALICE);

        assertEquals("tm-1:soon:" + STARTS.toEpochSecond(), soon.externalId());
        assertEquals("Starting soon: Band Live", soon.title());
        assertEquals("tm-1:onsale:" + ON_SALE.toEpochSecond(), onSale.externalId());
        assertEquals("On sale soon: Band Live", onSale.title());

        // Rescheduled: a new start time is a new reminder, not a repeat of the old one
        e.setStartsAt(STARTS.plusDays(7));
        assertNotEquals(soon.externalId(), Alerts.startingSoon(e, ALICE).externalId());
    }

    // ---------- untrusted text ----------

    @Test
    void textFromASourceCannotPingAnyoneOrBreakTheFormatting() {
        StoredEvent e = concert();
        e.setTitle("@everyone *FREE* tickets");
        e.setVenue("<@&123> Hall_1");

        Alert a = Alerts.newEvent(e, ALICE);

        assertEquals("New: @​everyone \\*FREE\\* tickets", a.title());
        assertTrue(a.body().contains("**Where:** <@​&123> Hall\\_1"));
        assertFalse(a.title().contains("@everyone"));
        assertFalse(a.body().contains("<@&123>"));
        assertTrue(a.body().startsWith("<@" + ALICE + ">"), "the subscriber's own mention still works");
    }

    @Test
    void textFromASourceCannotBecomeALinkOrStartALine() {
        StoredEvent e = concert();
        e.setTitle("[Free tickets](https://evil.example)");
        e.setVenue("Hall\n# Big heading\n> quote");

        Alert a = Alerts.newEvent(e, ALICE);

        assertEquals("New: \\[Free tickets](https://evil.example)", a.title());
        assertTrue(a.body().contains("**Where:** Hall # Big heading > quote\n"),
                "flattened onto its own line, behind the label");
    }

    @Test
    void urlsAreLeftExactlyAsGiven() {
        StoredEvent e = concert();
        e.setUrl("https://example.com/a_b?x=1&y=2");

        assertTrue(Alerts.newEvent(e, ALICE).body().endsWith("\nhttps://example.com/a_b?x=1&y=2"));
    }

    // ---------- first-poll summary ----------

    @Test
    void aSummaryOfManyShowsListsTheSoonestFive() {
        List<Event> shows = new ArrayList<>();
        for (int i = 7; i >= 1; i--) {   // deliberately latest first
            shows.add(new Event(SourceType.TICKETMASTER, "tm-" + i, "Show " + i, STARTS.plusDays(i), null,
                    "Venue " + i, null, "https://example.com/" + i, null));
        }

        Alert a = Alerts.baseline(concertFeed(), ALICE, shows);

        assertEquals("ticketmaster", a.externalSource());
        assertEquals("Now watching: concerts within 25 mi of 94110 · Rock", a.title());
        List<String> lines = List.of(a.body().split("\n"));
        assertEquals("<@" + ALICE + ">", lines.get(0));
        assertEquals("7 shows right now, soonest first:", lines.get(1));
        assertEquals(List.of("Show 1", "Show 2", "Show 3", "Show 4", "Show 5"),
                lines.subList(2, 7).stream().map(l -> l.substring(2, l.indexOf(" — "))).toList());
        assertEquals("• Show 1 — <t:" + STARTS.plusDays(1).toEpochSecond() + ":f> — Venue 1 — https://example.com/1",
                lines.get(2));
    }

    @Test
    void aSummaryOfDealsListsTheCheapestWithPrices() {
        List<Event> deals = List.of(
                new Event(SourceType.CHEAPSHARK, "2", "Hades II", null, null, null, new BigDecimal("19.99"), "https://d/2", null),
                new Event(SourceType.CHEAPSHARK, "1", "Hades", null, null, null, new BigDecimal("9.99"), "https://d/1", null));

        Alert a = Alerts.baseline(dealFeed(), ALICE, deals);

        assertEquals("Now watching: hades under $20.00", a.title());
        assertTrue(a.body().contains("2 deals right now:\n• Hades — $9.99 — https://d/1\n• Hades II — $19.99 — https://d/2"));
    }

    @Test
    void aSummaryOfNothingSaysSo() {
        Alert a = Alerts.baseline(dealFeed(), ALICE, List.of());

        assertEquals("<@" + ALICE + ">\nNothing matches right now. You'll be mentioned when something does.", a.body());
    }

    @Test
    void oneMatchIsSingular() {
        Event one = new Event(SourceType.CHEAPSHARK, "1", "Hades", null, null, null, new BigDecimal("9.99"), null, null);

        assertTrue(Alerts.baseline(dealFeed(), ALICE, List.of(one)).body().contains("1 deal right now:"));
    }

    @Test
    void describesEachKindOfFeed() {
        assertEquals("concerts within 25 mi of 94110 · Rock",
                Alerts.describe(SourceType.TICKETMASTER, concertFeed().getParams()));
        assertEquals("concerts within 10 mi of 10001",
                Alerts.describe(SourceType.TICKETMASTER, Map.of("radius", "10", "zip", "10001", "lat", "1", "lon", "2")));
        assertEquals("hades under $20.00", Alerts.describe(SourceType.CHEAPSHARK, dealFeed().getParams()));
    }

    // ---------- helpers ----------

    private static Feed concertFeed() {
        Feed f = new Feed();
        f.setSourceType(SourceType.TICKETMASTER);
        f.setParams(new HashMap<>(Map.of("lat", "37.750021", "lon", "-122.415201", "radius", "25",
                "segment", "Music", "classification", "Rock", "zip", "94110")));
        return f;
    }

    private static Feed dealFeed() {
        Feed f = new Feed();
        f.setSourceType(SourceType.CHEAPSHARK);
        f.setParams(new HashMap<>(Map.of("title", "hades", "maxPrice", "20.00")));
        return f;
    }

    private static String full(OffsetDateTime t) {
        return "<t:" + t.toEpochSecond() + ":F>";
    }

    private static StoredEvent concert() {
        StoredEvent e = new StoredEvent();
        e.setSource(SourceType.TICKETMASTER);
        e.setExternalId("tm-1");
        e.setTitle("Band Live");
        e.setStartsAt(STARTS);
        e.setOnSaleAt(ON_SALE);
        e.setVenue("The Fillmore, San Francisco");
        e.setPrice(new BigDecimal("49.50"));
        e.setUrl("https://www.ticketmaster.com/event/tm-1");
        e.setStatus(EventStatus.SCHEDULED);
        e.setContentHash("hash");
        return e;
    }

    private static StoredEvent deal() {
        StoredEvent e = new StoredEvent();
        e.setSource(SourceType.CHEAPSHARK);
        e.setExternalId("200");
        e.setTitle("Hades");
        e.setPrice(new BigDecimal("9.99"));
        e.setUrl("https://www.cheapshark.com/redirect?dealID=abc%3D");
        e.setStatus(EventStatus.SCHEDULED);
        e.setContentHash("hash");
        return e;
    }
}
