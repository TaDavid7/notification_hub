package com.david.notification_hub.delivery_logs;

import com.david.notification_hub.notification_request.NotificationRequest;
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

    @Column(name = "attempt", nullable = false)
    private Integer attempt = 1;

    @Column(name = "succeeded", nullable = false)
    private Boolean succeeded;

    @Column(name = "provider_response")
    private String providerResponse;

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

    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }

    public Boolean getSucceeded() { return succeeded; }
    public void setSucceeded(Boolean succeeded) { this.succeeded = succeeded; }

    public String getProviderResponse() { return providerResponse; }
    public void setProviderResponse(String apnsResponse) { this.providerResponse = providerResponse; }

    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
}
