package com.david.notification_hub.delivery_logs;

import com.david.notification_hub.notification_request.NotificationRequest;
import com.david.notification_hub.device.Device;
import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "delivery_logs")
public class DeliveryLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "request_id", nullable = false)
    private NotificationRequest request;

    @ManyToOne
    @JoinColumn(name = "device_id")
    private Device device; // nullable

    @Column(name = "attempt", nullable = false)
    private Integer attempt = 1;

    @Column(name = "succeeded", nullable = false)
    private Boolean succeeded;

    @Column(name = "apns_response")
    private String apnsResponse;

    @Column(name = "error_msg")
    private String errorMsg;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public DeliveryLog() {}

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }

    public NotificationRequest getRequest() { return request; }
    public void setRequest(NotificationRequest request) { this.request = request; }

    public Device getDevice() { return device; }
    public void setDevice(Device device) { this.device = device; }

    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }

    public Boolean getSucceeded() { return succeeded; }
    public void setSucceeded(Boolean succeeded) { this.succeeded = succeeded; }

    public String getApnsResponse() { return apnsResponse; }
    public void setApnsResponse(String apnsResponse) { this.apnsResponse = apnsResponse; }

    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
}
