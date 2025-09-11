package com.david.notification_hub.notification_request;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Optional;

@RestController
@RequestMapping("/notifications")
public class NotificationRequestController{
    private final NotificationRequestRepository repo;

    public NotificationRequestController(NotificationRequestRepository repo){
        this.repo = repo;
    }

    //dto
    public static class CreateNotification{
        public String targetType;
        public String targetRef;
        public String title;
        public String body;
        public String priority;
    }

    @PostMapping
    public ResponseEntity<NotificationRequest> create(@RequestBody CreateNotification body){
        NotificationRequest r = new NotificationRequest();
        r.setTargetType(body.targetType);
        r.setTargetRef(body.targetRef);
        r.setTitle(body.title);
        r.setBody(body.body);
        if(body.priority != null && !body.priority.isBlank()){
            r.setPriority(body.priority);
        }

        NotificationRequest saved = repo.save(r);
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
