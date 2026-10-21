package com.david.notification_hub.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Looks up the {@link Source} for a feed's type.
 *
 * There is no list to maintain: Spring hands the constructor every {@code Source} bean
 * in the context, so a new source registers itself just by existing.
 */
@Component
public class SourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(SourceRegistry.class);

    private final Map<SourceType, Source> byType;

    /**
     * @param sources every Source bean; Spring passes an empty list when there are none.
     *                Two claiming one type stops startup, since there is no right way to
     *                choose between them.
     */
    public SourceRegistry(List<Source> sources) {
        Map<SourceType, Source> map = new EnumMap<>(SourceType.class);
        for (Source source : sources) {
            SourceType type = Objects.requireNonNull(source.type(),
                    () -> source.getClass().getName() + ".type() returned null");

            Source previous = map.putIfAbsent(type, source);
            if (previous != null) {
                throw new IllegalStateException("Two sources claim " + type + ": "
                        + previous.getClass().getName() + " and " + source.getClass().getName());
            }
        }
        this.byType = Collections.unmodifiableMap(map);

        // Not fatal: a type can exist before its source is written, and its feeds then
        // just go unpolled. EnumSet.copyOf would throw on an empty key set, hence removeAll.
        Set<SourceType> missing = EnumSet.allOf(SourceType.class);
        missing.removeAll(byType.keySet());

        log.info("Sources registered: {}", byType.keySet());
        if (!missing.isEmpty()) {
            log.warn("No source registered for {}; feeds of those types will not be polled", missing);
        }
    }

    /** The types that have a source - the only ones worth polling. */
    public Set<SourceType> types() {
        return byType.keySet();
    }

    /** @throws IllegalArgumentException if no source serves this type */
    public Source get(SourceType type) {
        Source source = byType.get(type);
        if (source == null) {
            throw new IllegalArgumentException("No source registered for " + type);
        }
        return source;
    }
}
