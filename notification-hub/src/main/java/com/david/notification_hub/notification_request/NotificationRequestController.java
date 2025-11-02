package com.david.notification_hub.notification_request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping(path = "/api/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
public class NotificationRequestController {

    private final NotificationRequestRepository repo;
    private final NotificationService service;

    public NotificationRequestController(NotificationRequestRepository repo, NotificationService service) {
        this.repo = repo;
        this.service = service;
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

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(@Valid @RequestBody CreateNotification body) {

        String channel  = (body.channel  == null || body.channel.isBlank())
                ? "DISCORD" : body.channel.toUpperCase(Locale.ROOT);
        String priority = (body.priority == null || body.priority.isBlank())
                ? "NORMAL"  : body.priority.toUpperCase(Locale.ROOT);

        NotificationRequest r = new NotificationRequest();
        r.setTitle(body.title);
        r.setBody(body.body);
        r.setPriority(priority);
        r.setChannel(channel);
        r.setStatus("QUEUED");
        r.setExternalSource(body.externalSource);
        r.setExternalId(body.externalId);

        NotificationRequest saved;
        try {
            // First insert wins
            saved = repo.save(r);

            // Process (send). If it throws, we degrade to FAILED but still return the created row.
            try {
                service.process(saved);
            } catch (Exception ex) {
                saved.setStatus("FAILED");
                repo.save(saved);
            }

            URI location = URI.create("/api/notifications/" + saved.getId());
            return ResponseEntity.created(location).body(saved);

        } catch (DataIntegrityViolationException dup) {
            // Unique index hit → already exists → return the canonical existing row as idempotent success
            Optional<NotificationRequest> existing = repo.findByExternalSourceAndExternalIdAndChannel(
                    body.externalSource, body.externalId, channel
            );
            if (existing.isPresent()) {
                return ResponseEntity.ok(Map.of(
                        "id", existing.get().getId(),
                        "status", existing.get().getStatus(),
                        "channel", existing.get().getChannel()
                ));
            }
            // If we somehow can’t find it, bubble up the error for visibility
            throw dup;
        }
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
