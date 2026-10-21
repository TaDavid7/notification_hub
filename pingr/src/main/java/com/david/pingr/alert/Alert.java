package com.david.pingr.alert;

/**
 * One message bound for the outbox: its dedupe key (externalSource plus externalId,
 * per user and channel) and its text.
 */
public record Alert(String externalSource, String externalId, String title, String body) {}
