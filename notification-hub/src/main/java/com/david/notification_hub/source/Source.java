package com.david.notification_hub.source;

import com.david.notification_hub.feed.Feed;

import java.util.List;

/**
 * An external API Pingr polls, such as Ticketmaster or CheapShark.
 *
 * A source fetches and normalises, and that is all: no database writes, no
 * notifications, no deciding what is new. Change detection and delivery sit downstream
 * and work the same for every source, so adding one means a new {@link SourceType}
 * constant and one {@code @Component} class implementing this. {@link SourceRegistry}
 * picks it up from there.
 *
 * Implementations may be called from more than one thread at once.
 */
public interface Source {

    /** The type this source serves. Exactly one source may claim each type. */
    SourceType type();

    /**
     * Runs the feed's query against the API.
     *
     * Only {@code feed.getParams()} may affect the result. Feeds with equal params share
     * one call, and whichever of them is passed in has to produce the same answer.
     *
     * Throws on failure rather than returning an empty list. Empty means "nothing
     * matched", and the poller has to tell that apart from an outage to know when to
     * back off.
     *
     * @return every match, each with {@code source() == type()}; never null
     */
    List<Event> fetch(Feed feed);
}
