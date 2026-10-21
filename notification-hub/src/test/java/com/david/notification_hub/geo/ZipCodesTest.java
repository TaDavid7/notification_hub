package com.david.notification_hub.geo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZipCodesTest {

    private static final ZipCodes ZIPS = new ZipCodes();

    @Test
    void loadsEveryZctaInTheFile() {
        // The 2020 gazetteer lists 33,144
        assertEquals(33_144, ZIPS.size());
    }

    @Test
    void returnsTheFilesCoordinatesExactly() {
        assertEquals(new ZipCodes.Point("37.750021", "-122.415201"), ZIPS.find("94110").orElseThrow());
        assertEquals(new ZipCodes.Point("40.750636", "-73.997177"), ZIPS.find(" 10001 ").orElseThrow());
    }

    @Test
    void keepsLeadingZeros() {
        // Puerto Rico; read as a number this would be 601 and never match
        assertEquals(new ZipCodes.Point("18.180555", "-66.749961"), ZIPS.find("00601").orElseThrow());
    }

    @Test
    void anEasternLongitudeHasNoPlusSign() {
        // Guam - the gazetteer writes it "+144.747191"; a + would make it a different
        // params string from anything else, for no reason
        assertEquals("144.747191", ZIPS.find("96910").orElseThrow().lon());
    }

    @Test
    void unknownOrMalformedFindsNothing() {
        assertTrue(ZIPS.find("00000").isEmpty());
        assertTrue(ZIPS.find("9411").isEmpty());
        assertTrue(ZIPS.find("abcde").isEmpty());
        assertTrue(ZIPS.find("").isEmpty());
        assertTrue(ZIPS.find(null).isEmpty());
    }
}
