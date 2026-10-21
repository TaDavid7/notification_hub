package com.david.notification_hub.source;

import com.david.notification_hub.feed.Feed;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No database here. The Spring half uses ApplicationContextRunner, which starts a
 * context holding only the classes named, so it can show beans being collected without
 * booting the app.
 */
class SourceRegistryTest {

    // ---------- the registry itself ----------

    @Test
    void findsEachSourceByItsType() {
        Source tm = new FakeTicketmaster();
        Source cs = new FakeCheapShark();

        SourceRegistry registry = new SourceRegistry(List.of(tm, cs));

        assertSame(tm, registry.get(SourceType.TICKETMASTER));
        assertSame(cs, registry.get(SourceType.CHEAPSHARK));
    }

    @Test
    void anUnregisteredTypeIsAClearError() {
        SourceRegistry registry = new SourceRegistry(List.of(new FakeTicketmaster()));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> registry.get(SourceType.CHEAPSHARK));
        assertTrue(ex.getMessage().contains("CHEAPSHARK"));
    }

    @Test
    void twoSourcesForOneTypeAreRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new SourceRegistry(List.of(new FakeTicketmaster(), new OtherTicketmaster())));

        // Names both, so whoever hits this knows which two classes to look at
        assertTrue(ex.getMessage().contains("FakeTicketmaster"));
        assertTrue(ex.getMessage().contains("OtherTicketmaster"));
    }

    @Test
    void aSourceWithNoTypeIsRejected() {
        Source typeless = new FakeTicketmaster() {
            @Override public SourceType type() { return null; }
        };

        assertThrows(NullPointerException.class, () -> new SourceRegistry(List.of(typeless)));
    }

    @Test
    void noSourcesAtAllIsAllowed() {
        // A type can exist before its source does; that must not stop the app
        assertDoesNotThrow(() -> new SourceRegistry(List.of()));
    }

    // ---------- Spring wiring ----------

    @Test
    void springHandsTheRegistryEverySourceBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(SourceRegistry.class, FakeTicketmaster.class, FakeCheapShark.class)
                .run(ctx -> {
                    SourceRegistry registry = ctx.getBean(SourceRegistry.class);
                    assertInstanceOf(FakeTicketmaster.class, registry.get(SourceType.TICKETMASTER));
                    assertInstanceOf(FakeCheapShark.class, registry.get(SourceType.CHEAPSHARK));
                });
    }

    @Test
    void aDuplicateTypeStopsStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(SourceRegistry.class, FakeTicketmaster.class, OtherTicketmaster.class)
                .run(ctx -> {
                    Throwable failure = ctx.getStartupFailure();
                    assertNotNull(failure, "the app must not start with two sources for one type");
                    assertInstanceOf(IllegalStateException.class,
                            NestedExceptionUtils.getMostSpecificCause(failure));
                });
    }

    // ---------- fakes ----------

    static class FakeTicketmaster implements Source {
        @Override public SourceType type() { return SourceType.TICKETMASTER; }
        @Override public List<Event> fetch(Feed feed) { return List.of(); }
    }

    static class OtherTicketmaster implements Source {
        @Override public SourceType type() { return SourceType.TICKETMASTER; }
        @Override public List<Event> fetch(Feed feed) { return List.of(); }
    }

    static class FakeCheapShark implements Source {
        @Override public SourceType type() { return SourceType.CHEAPSHARK; }
        @Override public List<Event> fetch(Feed feed) { return List.of(); }
    }
}
