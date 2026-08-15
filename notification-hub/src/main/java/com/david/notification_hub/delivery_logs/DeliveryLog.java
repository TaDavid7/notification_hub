package com.david.notification_hub.delivery_logs;

import com.david.notification_hub.notification_request.NotificationRequest;
import jakarta.persistence.*; //all JPA annotations Entity, Id, Column
import java.time.OffsetDateTime;

//JPA entity is a Java class that maps directly onto database table
@Entity //tells JPA/Hibernate this class reps a database table
@Table(name = "delivery_logs") //names the table as delivery_logs
public class DeliveryLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne //foreign-key relationship, each attempt get its own log row
    @JoinColumn(name = "request_id", nullable = false) //foreign key column called request_id
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
    public void setProviderResponse(String providerResponse) { this.providerResponse = providerResponse; }

    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
}
