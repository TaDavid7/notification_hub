package com.david.notification_hub.device;

import com.david.notification_hub.user.User;
import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "devices")
public class Device {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    public User user;

    @Column(name = "platform", nullable = false)
    public String platform; // e.g., "IOS"

    @Column(name = "apns_token", nullable = false, unique = true)
    public String apnsToken;

    @Column(name = "bundle_id", nullable = false)
    public String bundleId;

    @Column(name = "is_sandbox", nullable = false)
    public boolean isSandbox = true;

    @Column(name = "last_seen_at")
    public OffsetDateTime lastSeenAt;

    public Device() {}
}
