package com.david.pingr.source;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The content hash decides what counts as an update, so these pin down both halves:
 * real changes must move it, and noise must not.
 */
class EventTest {

    private static final OffsetDateTime STARTS = OffsetDateTime.of(2026, 12, 31, 20, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime ON_SALE = OffsetDateTime.of(2026, 11, 1, 17, 0, 0, 0, ZoneOffset.UTC);

    private static Event concert(OffsetDateTime startsAt, OffsetDateTime onSaleAt, String venue, String price) {
        return concert(startsAt, onSaleAt, venue, price, EventStatus.SCHEDULED);
    }

    private static Event concert(OffsetDateTime startsAt, OffsetDateTime onSaleAt, String venue, String price,
                                 EventStatus status) {
        return new Event(SourceType.TICKETMASTER, "tm-1", "Band Live", startsAt, onSaleAt, venue,
                price == null ? null : new BigDecimal(price), "https://example.com/tm-1", status);
    }

    private static Event baseline() {
        return concert(STARTS, ON_SALE, "The Fillmore", "49.50");
    }

    // ---------- what must move the hash ----------

    @Test
    void equalContentHashesEqually() {
        assertEquals(baseline().contentHash(), baseline().contentHash());
        assertEquals(64, baseline().contentHash().length(), "SHA-256 as hex");
    }

    @Test
    void aPriceChangeIsAChange() {
        assertNotEquals(baseline().contentHash(),
                concert(STARTS, ON_SALE, "The Fillmore", "39.50").contentHash());
    }

    @Test
    void aNewDateIsAChange() {
        assertNotEquals(baseline().contentHash(),
                concert(STARTS.plusDays(1), ON_SALE, "The Fillmore", "49.50").contentHash());
    }

    @Test
    void aNewOnSaleDateIsAChange() {
        assertNotEquals(baseline().contentHash(),
                concert(STARTS, ON_SALE.plusHours(2), "The Fillmore", "49.50").contentHash());
    }

    @Test
    void aNewVenueIsAChange() {
        assertNotEquals(baseline().contentHash(),
                concert(STARTS, ON_SALE, "The Warfield", "49.50").contentHash());
    }

    @Test
    void aPriceAppearingIsAChange() {
        // Ticketmaster often lists an event before it has prices
        assertNotEquals(concert(STARTS, ON_SALE, "The Fillmore", null).contentHash(),
                baseline().contentHash());
    }

    @Test
    void aCancellationIsAChangeEvenWhenNothingElseIs() {
        // The case the status exists for: Ticketmaster can cancel an event and leave its
        // date, venue and price exactly as they were
        for (EventStatus status : EventStatus.values()) {
            if (status == EventStatus.SCHEDULED) continue;
            assertNotEquals(baseline().contentHash(),
                    concert(STARTS, ON_SALE, "The Fillmore", "49.50", status).contentHash(),
                    status + " must read as a change");
        }
    }

    @Test
    void fieldsCannotBleedIntoEachOther() {
        // Run together without separators, a venue of "1.00" and no price would read the
        // same as no venue and a price of 1.00
        assertNotEquals(concert(STARTS, ON_SALE, "1.00", null).contentHash(),
                concert(STARTS, ON_SALE, null, "1.00").contentHash());
    }

    // ---------- what must not ----------

    @Test
    void titleAndUrlAreNotContent() {
        Event retitled = new Event(SourceType.TICKETMASTER, "tm-1", "Band Live (Late Show)", STARTS, ON_SALE,
                "The Fillmore", new BigDecimal("49.50"), "https://example.com/tm-1?utm_source=x",
                EventStatus.SCHEDULED);

        assertEquals(baseline().contentHash(), retitled.contentHash());
    }

    @Test
    void theSameMomentInAnotherOffsetIsNoChange() {
        OffsetDateTime samePacific = STARTS.withOffsetSameInstant(ZoneOffset.ofHours(-8));

        assertEquals(baseline().contentHash(),
                concert(samePacific, ON_SALE, "The Fillmore", "49.50").contentHash());
    }

    @Test
    void trailingZerosAndSubCentNoiseAreNoChange() {
        assertEquals(baseline().contentHash(), concert(STARTS, ON_SALE, "The Fillmore", "49.5").contentHash());
        assertEquals(baseline().contentHash(), concert(STARTS, ON_SALE, "The Fillmore", "49.4999").contentHash());
    }

    @Test
    void surroundingWhitespaceIsNoChange() {
        assertEquals(baseline().contentHash(),
                concert(STARTS, ON_SALE, "  The Fillmore \n", "49.50").contentHash());
    }

    // ---------- normalisation ----------

    @Test
    void priceIsRoundedToCents() {
        assertEquals(new BigDecimal("20.00"), concert(STARTS, ON_SALE, null, "19.995").price());
    }

    @Test
    void blankOptionalFieldsBecomeNull() {
        Event e = new Event(SourceType.CHEAPSHARK, "game-1", "Hades", null, null, " ", null, "", null);

        assertNull(e.venue());
        assertNull(e.url());
    }

    @Test
    void noStatusMeansScheduled() {
        Event e = concert(STARTS, ON_SALE, "The Fillmore", "49.50", null);

        assertEquals(EventStatus.SCHEDULED, e.status());
        assertEquals(baseline().contentHash(), e.contentHash());
    }

    @Test
    void identityAndTitleAreRequired() {
        assertThrows(NullPointerException.class,
                () -> new Event(null, "x", "t", null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Event(SourceType.CHEAPSHARK, " ", "t", null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Event(SourceType.CHEAPSHARK, "x", null, null, null, null, null, null, null));
    }
}
