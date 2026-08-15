package com.david.notification_hub.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for tests that need a real database.
 *
 * The container is a static singleton started once per JVM rather than a
 * @Container field, which would stop and restart Postgres for every test
 * class. Testcontainers' Ryuk sidecar cleans it up when the JVM exits.
 *
 * Image is pinned to the same postgres:15 that docker-compose.yml runs, so
 * tests and local development can't drift apart.
 */
public abstract class AbstractPostgresTest {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15")
                    .withDatabaseName("notification_hub")
                    .withUsername("postgres")
                    .withPassword("pass");

    static {
        POSTGRES.start();
    }

    // Points Spring at the container's randomly-assigned port
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
