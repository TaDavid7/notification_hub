package com.david.notification_hub.notification_request;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Optional;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
public class NotificationRequestController{
    private final NotificationRequestRepository repo;
    private final NotificationService service;

    public NotificationRequestController(NotificationRequestRepository repo, NotificationService service){
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
        NotificationRequest r = new NotificationRequest();
        r.setTitle(body.title);
        r.setBody(body.body);
        if(body.priority != null && !body.priority.isBlank()){
            r.setPriority(body.priority);
        }
        r.setChannel(body.channel);

        NotificationRequest saved = repo.save(r);
        service.process(saved);
        return ResponseEntity.created(URI.create("/notifications/" + saved.getId())).body(saved);
    }
    @GetMapping("/{id}")
    public ResponseEntity<NotificationRequest> get(@PathVariable Long id) {
        Optional<NotificationRequest> maybe = repo.findById(id);

        if (maybe.isPresent()) {
            return ResponseEntity.ok(maybe.get());
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}
