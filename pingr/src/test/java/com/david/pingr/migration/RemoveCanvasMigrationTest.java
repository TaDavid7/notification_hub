package com.david.pingr.migration;

import com.david.pingr.support.AbstractPostgresTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers V7__remove_canvas.sql, which deletes data rather than changing structure.
 *
 * The Spring context tests only ever see V7 run against an empty table, which proves
 * nothing about a DELETE. This migrates a scratch database to V6, seeds the rows
 * production actually has, then applies V7 and checks what survived.
 */
class RemoveCanvasMigrationTest extends AbstractPostgresTest {

    private static final String DB = "v7_migration_test";

    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void freshDatabase() {
        // A database of its own, so the schema the Spring context tests share is never
        // rolled back to V6 underneath them
        JdbcTemplate admin = new JdbcTemplate(dataSource(POSTGRES.getDatabaseName()));
        admin.execute("DROP DATABASE IF EXISTS " + DB);
        admin.execute("CREATE DATABASE " + DB);

        dataSource = dataSource(DB);
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void deletesCanvasRowsAndTheirDeliveryLogsAndKeepsEverythingElse() {
        migrateTo("6");

        long announcement = insertRequest("canvas:announcement", "1");
        long assignment = insertRequest("canvas:calendar:assignment", "2");
        long manual = insertRequest("manual", "3");
        long preV5 = insertRequest(null, null);
        insertLog(announcement);
        insertLog(assignment);
        insertLog(manual);

        migrateTo("7");

        assertEquals(List.of(manual, preV5),
                jdbc.queryForList("SELECT id FROM notification_requests ORDER BY id", Long.class));
        assertEquals(List.of(manual),
                jdbc.queryForList("SELECT request_id FROM delivery_logs", Long.class),
                "a Canvas row's delivery history must go with it, and nothing else's");
    }

    @Test
    void onlyAPrefixMatchCounts() {
        migrateTo("6");

        // 'canvas' appearing anywhere but the start is not a Canvas row
        long lookalike = insertRequest("manual:canvas:announcement", "1");

        migrateTo("7");

        assertEquals(List.of(lookalike),
                jdbc.queryForList("SELECT id FROM notification_requests", Long.class));
    }

    private void migrateTo(String version) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }

    private long insertRequest(String externalSource, String externalId) {
        return jdbc.queryForObject("""
                INSERT INTO notification_requests (title, body, external_source, external_id)
                VALUES ('title', 'body', ?, ?)
                RETURNING id
                """, Long.class, externalSource, externalId);
    }

    private void insertLog(long requestId) {
        jdbc.update("INSERT INTO delivery_logs (request_id, succeeded) VALUES (?, true)", requestId);
    }

    private static DataSource dataSource(String database) {
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database;
        return new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
