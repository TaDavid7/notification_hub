package com.david.pingr.user;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * Someone who subscribes to feeds. The table dates from V1; V8 is the first thing to
 * use it.
 */
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The Discord user id. Unique, so there is one row per person.
    @Column(name = "external_id", nullable = false)
    private String externalId;

    // Where this user's alerts go. Null means the configured default webhook.
    @Column(name = "webhook_url")
    private String webhookUrl;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public User() {}

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
}
