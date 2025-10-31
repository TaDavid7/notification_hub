package com.david.notification_hub.user;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Optional;
import java.net.URI;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/users")
public class UserController{
    private final UserRepository repo;
    public UserController(UserRepository repo){
        this.repo = repo;
    }

    static class CreateUser {
        @NotBlank(message = "externalId is required")
        @Email(message = "externalId must be a valid email")
        public String externalId;
    }

    @PostMapping
    public ResponseEntity<User> create(@Valid @RequestBody CreateUser body){
        if(repo.findByExternalId(body.externalId).isPresent()){
            return ResponseEntity.status(409).build(); //no body
        }
        User saved = repo.save(new User(body.externalId));
        return ResponseEntity.created(URI.create("/users/" + saved.getId())).body(saved);
    }

    @GetMapping
    public List<User> list() {return repo.findAll(); }

    @GetMapping("/{id}")
    public ResponseEntity<User> get(@PathVariable Long id) {
        Optional<User> maybeUser = repo.findById(id);

        if (maybeUser.isPresent()) {
            return ResponseEntity.ok(maybeUser.get());
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}