package com.david.notification_hub.notification_request;

/**
 * Outbox lifecycle values for {@code notification_requests.status}.
 *
 * <pre>
 *   QUEUED  -> SENDING -> SENT
 *                     \-> RETRY -> SENDING -> ...
 *                              \-> DEAD   (attempts exhausted)
 * </pre>
 *
 * Kept as String constants rather than a JPA enum so the column stays plain TEXT
 * and the migrations remain readable in psql.
 */
public final class NotificationStatus {

    /** Accepted by the API, waiting for its first dispatch. */
    public static final String QUEUED = "QUEUED";

    /** Claimed by a dispatcher; a send is in flight. */
    public static final String SENDING = "SENDING";

    /** Provider accepted the message. Terminal. */
    public static final String SENT = "SENT";

    /** Last attempt failed and another is scheduled at next_attempt_at. */
    public static final String RETRY = "RETRY";

    /** Attempts exhausted. Terminal, and the row keeps last_error. */
    public static final String DEAD = "DEAD";

    private NotificationStatus() {}
}
