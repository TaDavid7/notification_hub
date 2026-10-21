package com.david.pingr.event;

import com.david.pingr.source.EventStatus;
import com.david.pingr.source.SourceType;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The last known state of one item a source returned - a concert, or a game's best deal.
 *
 * "Stored" to tell it apart from what a source hands back on each poll: this row is
 * shared by every feed that matched the item, and comparing its content_hash with a
 * fresh fetch is how a change is spotted.
 */
@Entity
@Table(name = "events")
public class StoredEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private SourceType source;

    // The source's own id for the item; unique together with source
    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "starts_at")
    private OffsetDateTime startsAt;

    // When tickets go on general sale. Null for sources with no such thing.
    @Column(name = "on_sale_at")
    private OffsetDateTime onSaleAt;

    @Column(name = "venue")
    private String venue;

    @Column(name = "price", precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "url")
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private EventStatus status = EventStatus.SCHEDULED;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    // How many times content_hash has changed. Not a JPA @Version: that would also count
    // title-only saves, and this is part of each update alert's outbox key.
    @Column(name = "revision", nullable = false)
    private int revision = 0;

    @Column(name = "first_seen_at", nullable = false)
    private OffsetDateTime firstSeenAt;

    // Last time any field actually changed. Hibernate skips the UPDATE, and so this
    // callback, when a save leaves the row as it was.
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public StoredEvent() {}

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (firstSeenAt == null) firstSeenAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }

    public SourceType getSource() { return source; }
    public void setSource(SourceType source) { this.source = source; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public OffsetDateTime getStartsAt() { return startsAt; }
    public void setStartsAt(OffsetDateTime startsAt) { this.startsAt = startsAt; }

    public OffsetDateTime getOnSaleAt() { return onSaleAt; }
    public void setOnSaleAt(OffsetDateTime onSaleAt) { this.onSaleAt = onSaleAt; }

    public String getVenue() { return venue; }
    public void setVenue(String venue) { this.venue = venue; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public EventStatus getStatus() { return status; }
    public void setStatus(EventStatus status) { this.status = status; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public int getRevision() { return revision; }
    public void setRevision(int revision) { this.revision = revision; }

    public OffsetDateTime getFirstSeenAt() { return firstSeenAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
