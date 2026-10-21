package com.david.pingr.source.ticketmaster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeohashTest {

    @Test
    void matchesTheReferenceExample() {
        // The worked example from the geohash spec (Jutland, Denmark)
        assertEquals("u4pruydqqvj", Geohash.encode(57.64911, 10.40744, 11));
    }

    @Test
    void theOriginAndTheCornersOfTheMap() {
        assertEquals("s0000000", Geohash.encode(0, 0, 8));
        assertEquals("00000000", Geohash.encode(-90, -180, 8));
        assertEquals("zzzzzzzz", Geohash.encode(90, 180, 8));
    }

    @Test
    void westAndNorthOfTheOriginComesOutRight() {
        // San Francisco's well-known cell; catches a swapped lat/lon or sign error
        assertTrue(Geohash.encode(37.7749, -122.4194, 9).startsWith("9q8yy"));
    }

    @Test
    void aShorterHashIsAPrefixOfALongerOne() {
        String nine = Geohash.encode(40.7128, -74.0060, 9);
        assertEquals(nine.substring(0, 5), Geohash.encode(40.7128, -74.0060, 5));
    }
}
