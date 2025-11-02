package com.david.notification_hub.notification_request;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface NotificationRequestRepository extends JpaRepository<NotificationRequest, Long> {
    Optional<NotificationRequest> findByExternalSourceAndExternalIdAndChannel(String externalSource, String externalId, String channel);
}