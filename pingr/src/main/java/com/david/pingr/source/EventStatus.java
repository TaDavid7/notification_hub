package com.david.pingr.source;

/**
 * Whether an event is still going ahead as planned. Stored by name in
 * {@code events.status}.
 *
 * Only states a subscriber should be told about are told apart. Everything else,
 * including a sales window opening or closing, is SCHEDULED: on-sale timing has its own
 * reminder, and an alert every time sales flip on or off would be noise.
 */
public enum EventStatus {
    SCHEDULED,
    POSTPONED,
    RESCHEDULED,
    CANCELED
}
