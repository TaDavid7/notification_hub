package com.david.pingr.polling;

import com.david.pingr.source.SourceType;

/**
 * One distinct query, as claimed for a poll.
 *
 * @param paramsJson the params as Postgres printed them, passed back as-is to find the
 *                   group's feeds
 * @param failures   failed polls in a row before this one
 */
public record PollGroup(long id, SourceType sourceType, String paramsJson, int failures) {}
