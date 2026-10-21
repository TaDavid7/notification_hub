package com.david.notification_hub.event;

import com.david.notification_hub.source.SourceType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface StoredEventRepository extends JpaRepository<StoredEvent, Long> {

    /**
     * The stored state of one item, locked ({@code SELECT ... FOR UPDATE}) until the
     * caller's transaction ends. Two groups of feeds can return the same event in the
     * same moment; the lock makes the second wait and then see the first one's change,
     * rather than both detecting it and both bumping the revision.
     *
     * Must be called inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<StoredEvent> findBySourceAndExternalId(SourceType source, String externalId);
}
