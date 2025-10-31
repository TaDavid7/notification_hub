package com.david.notification_hub.notification_request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
public class NotificationRequestController{

@RequestMapping(path = "/api/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
public class NotificationRequestController {


    private final NotificationRequestRepository repo;
    private final NotificationService service;

    public NotificationRequestController(NotificationRequestRepository repo, NotificationService service) {
        this.repo = repo;
        this.service = service;
    }


    //dto
    public static class CreateNotification{
        @NotBlank
        public String title;
        @NotBlank
        public String body;
        @NotBlank
        public String priority;
        @NotBlank
        public String channel;
    }


    @PostMapping("/api/notifications")
    public ResponseEntity<NotificationRequest> create(@Valid @RequestBody CreateNotification body){

    // DTO for create
    public static class CreateNotification {
        @NotBlank public String title;
        @NotBlank public String body;
        public String priority;   // optional: NORMAL | HIGH
        public String channel;    // optional: DISCORD | SLACK
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<NotificationRequest> create(@Valid @RequestBody CreateNotification body) {
        // Apply safe defaults to satisfy NOT NULL columns and business logic
        String priority = (body.priority == null || body.priority.isBlank()) ? "NORMAL"  : body.priority;
        String channel  = (body.channel  == null || body.channel.isBlank())  ? "DISCORD" : body.channel;


        NotificationRequest r = new NotificationRequest();
        r.setTitle(body.title);
        r.setBody(body.body);
        r.setPriority(priority);
        r.setChannel(channel);
        r.setStatus("QUEUED"); // make it explicit so DB/JPA never see null

        // 1) Persist first, outside of any controller-level transaction
        NotificationRequest saved = repo.save(r);

        // 2) Attempt to process in a separate layer/transaction.
        // If the send fails, do NOT propagate exception — we want the create to remain.
        try {
            service.process(saved); // If this is @Transactional, it will run in its own tx.
        } catch (Exception ex) {
            try {
                // Make sure failure is reflected but never abort the create
                saved.setStatus("FAILED");
                repo.save(saved);
            } catch (Exception ignore) {
                // swallow — we still return the created resource
            }
        }

        URI location = URI.create("/api/notifications/" + saved.getId());
        return ResponseEntity.created(location).body(saved);
    }

    @GetMapping("/{id}")
    public ResponseEntity<NotificationRequest> get(@PathVariable Long id) {
        Optional<NotificationRequest> maybe = repo.findById(id);
        return maybe.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // Optional: simple list for sanity checks in a browser
    @GetMapping
    public List<NotificationRequest> list() {
        return repo.findAll();
    }
}
