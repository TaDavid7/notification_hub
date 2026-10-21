package com.david.notification_hub.event;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StoredEventRepository extends JpaRepository<StoredEvent, Long> {}
