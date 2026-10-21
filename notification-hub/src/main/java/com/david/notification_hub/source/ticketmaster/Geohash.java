package com.david.notification_hub.source.ticketmaster;

/**
 * Encodes a coordinate as a geohash, the form Ticketmaster's geoPoint parameter takes.
 *
 * A geohash halves the longitude and latitude ranges alternately, writing 1 when the
 * point is in the upper half and 0 when in the lower, then packs each 5 bits into one
 * base-32 character. Each extra character narrows the cell; 9 is about 5m across.
 */
final class Geohash {

    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";

    private Geohash() {}

    static String encode(double lat, double lon, int precision) {
        double[] latRange = {-90, 90};
        double[] lonRange = {-180, 180};
        StringBuilder out = new StringBuilder(precision);

        boolean lonBit = true; // bits alternate, starting with longitude
        int bits = 0;
        int ch = 0;
        while (out.length() < precision) {
            double[] range = lonBit ? lonRange : latRange;
            double value = lonBit ? lon : lat;
            double mid = (range[0] + range[1]) / 2;

            ch <<= 1;
            if (value >= mid) {
                ch |= 1;
                range[0] = mid;
            } else {
                range[1] = mid;
            }
            lonBit = !lonBit;

            if (++bits == 5) {
                out.append(BASE32.charAt(ch));
                bits = 0;
                ch = 0;
            }
        }
        return out.toString();
    }
}
