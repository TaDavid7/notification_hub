package com.david.notification_hub.notification_request;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@Entity
@Table(name = "notification_requests")
public class NotificationRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(name = "title", nullable = false)
    private String title;

    @NotBlank
    @Column(name = "body", nullable = false)
    private String body;

    @NotBlank
    @Column(name = "priority", nullable = false)
    private String priority = "NORMAL";

    @NotBlank
    @Column(name = "status", nullable = false)
    private String status = "QUEUED";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @NotBlank
    @Column(name = "channel", nullable = false)
    private String channel = "DISCORD";

    @NotBlank
    @Column(name = "external_source", nullable = false)
    private String externalSource;

    @NotBlank
    @Column(name = "external_id", nullable = false)
    private String externalId;     // e.g., Canvas item id as string

    // --- outbox bookkeeping (V6) ---

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    // When the dispatcher may next try this row. Null means "not queued".
    @Column(name = "next_attempt_at")
    private OffsetDateTime nextAttemptAt;

    // Why the last attempt failed. Kept so a DEAD row explains itself without
    // needing to join delivery_logs.
    @Column(name = "last_error")
    private String lastError;

    // Set when a dispatcher claims the row; the reaper uses it to spot rows
    // orphaned by an instance that died mid-send.
    @Column(name = "claimed_at")
    private OffsetDateTime claimedAt;


    public NotificationRequest() {}

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    public String getChannel() {
        return channel;
    }
    public void setChannel(String channel){
        this.channel = channel;
    }

    public String getExternalSource(){return externalSource;}
    public void setExternalSource(String externalSource){this.externalSource = externalSource;}

    public String getExternalId(){return externalId;}
    public void setExternalId(String externalId){this.externalId = externalId;}

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public OffsetDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(OffsetDateTime nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public OffsetDateTime getClaimedAt() { return claimedAt; }
    public void setClaimedAt(OffsetDateTime claimedAt) { this.claimedAt = claimedAt; }
}
