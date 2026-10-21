package com.david.notification_hub.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /** @param externalId the Discord user id */
    Optional<User> findByExternalId(String externalId);
}
