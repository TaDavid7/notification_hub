package com.david.notification_hub.user;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, unique = true)
    private String externalId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public User() {} // required by JPA
    public User(String externalId) {
        this.externalId = externalId;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public String getExternalId() { return externalId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
}