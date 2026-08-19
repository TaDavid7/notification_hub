package com.david.notification_hub.notification_request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping(path = "/api/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
public class NotificationRequestController {

    private final NotificationRequestRepository repo;
    private final NotificationIntakeService intake;

    public NotificationRequestController(NotificationRequestRepository repo, NotificationIntakeService intake) {
        this.repo = repo;
        this.intake = intake;
    }

    // DTO: only title/body strictly required; others defaulted
    public static class CreateNotification {
        @NotBlank public String title;
        @NotBlank public String body;
        public String priority;        // NORMAL | HIGH (default NORMAL)
        public String channel;         // DISCORD | SLACK (default DISCORD)
        @NotBlank public String externalSource;  // e.g., "canvas:announcement"
        @NotBlank public String externalId;      // e.g., "123456"
    }

    /**
     * Enqueues a notification. Returns 202, not 201: the row exists, but the send
     * happens on the dispatcher afterwards, so this response cannot honestly claim
     * the message was delivered.
     *
     * It used to send inline and return 201 even when the webhook had failed. Poll
     * {@code GET /api/notifications/{id}} for the real outcome - status walks
     * QUEUED -> SENDING -> SENT, or ends at DEAD with {@code lastError} set.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<NotificationRequest> create(@Valid @RequestBody CreateNotification body) {
        NotificationIntakeService.Result result = intake.enqueue(
                body.title, body.body, body.priority, body.channel, body.externalSource, body.externalId);

        NotificationRequest saved = result.request();
        URI location = URI.create("/api/notifications/" + saved.getId());

        // Already enqueued for this (source, id, channel): idempotent success, and the
        // caller gets the row that won rather than a duplicate.
        if (result.duplicate()) {
            return ResponseEntity.ok().location(location).body(saved);
        }
        return ResponseEntity.accepted().location(location).body(saved);
    }

    @GetMapping("/{id}")
    public ResponseEntity<NotificationRequest> get(@PathVariable Long id) {
        Optional<NotificationRequest> maybe = repo.findById(id);
        return maybe.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<NotificationRequest> list() {
        return repo.findAll();
    }
}
