package com.david.notification_hub.source;

/**
 * The external API a feed polls. Stored by name in {@code feeds.source_type} and
 * {@code events.source}, so renaming a constant means migrating those rows.
 */
public enum SourceType {
    TICKETMASTER,
    CHEAPSHARK
}
