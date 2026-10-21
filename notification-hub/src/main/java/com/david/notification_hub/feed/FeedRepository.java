package com.david.notification_hub.feed;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FeedRepository extends JpaRepository<Feed, Long> {

    List<Feed> findByUserIdOrderByIdAsc(Long userId);

    /**
     * Every feed asking exactly this query - one poll group. jsonb equality, so key order
     * in {@code paramsJson} doesn't matter.
     */
    @Query(value = """
            SELECT * FROM feeds
             WHERE source_type = :sourceType AND params = CAST(:paramsJson AS jsonb)
             ORDER BY id
            """, nativeQuery = true)
    List<Feed> findGroup(@Param("sourceType") String sourceType, @Param("paramsJson") String paramsJson);
}
