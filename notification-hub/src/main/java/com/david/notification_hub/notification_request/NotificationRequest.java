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
    @NotBlank
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

    @NotBlank
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @NotBlank
    @Column(name = "channel", nullable = false)
    private String channel = "DISCORD";

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
}
