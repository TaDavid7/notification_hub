package com.david.notification_hub.alert;

import com.david.notification_hub.event.StoredEvent;
import com.david.notification_hub.source.EventStatus;
import com.david.notification_hub.source.SourceType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

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

    // ---------- helpers ----------

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
