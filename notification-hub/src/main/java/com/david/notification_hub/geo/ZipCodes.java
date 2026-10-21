package com.david.notification_hub.geo;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * US zip code to coordinates, from the Census Bureau's ZCTA gazetteer bundled as
 * geo/zcta-2020.csv (its header says where it came from).
 *
 * Loaded once at startup - about 33,000 entries - so a lookup inside a Discord
 * interaction costs no network call against Discord's 3-second deadline.
 *
 * ZCTAs approximate USPS zip codes. A zip that only serves PO boxes or a single
 * building has no area of its own and won't be found.
 */
@Component
public class ZipCodes {

    private static final String RESOURCE = "geo/zcta-2020.csv";

    /**
     * Coordinates exactly as the file writes them, so the same zip always becomes the
     * same feed params and subscribers to one area share one poll.
     */
    public record Point(String lat, String lon) {}

    private final Map<String, Point> byZip;

    public ZipCodes() {
        this.byZip = load();
    }

    public Optional<Point> find(String zip) {
        return zip == null ? Optional.empty() : Optional.ofNullable(byZip.get(zip.strip()));
    }

    public int size() {
        return byZip.size();
    }

    private static Map<String, Point> load() {
        Map<String, Point> out = new HashMap<>(40_000);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new ClassPathResource(RESOURCE).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("zip,")) continue;
                String[] f = line.split(",");
                if (f.length != 3) {
                    throw new IllegalStateException(RESOURCE + " has a malformed line: " + line);
                }
                out.put(f[0], new Point(f[1], f[2]));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + RESOURCE, e);
        }
        return Map.copyOf(out);
    }
}
