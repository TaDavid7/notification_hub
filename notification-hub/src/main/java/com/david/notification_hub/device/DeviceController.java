package com.david.notification_hub.device;

import com.david.notification_hub.user.UserRepository;
import com.david.notification_hub.user.User;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.dao.DataIntegrityViolationException;


import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/devices")
public class DeviceController{
    private final DeviceRepository devices;
    private final UserRepository users;

    public DeviceController(DeviceRepository devices, UserRepository users) {
        this.devices = devices;
        this.users = users;
    }

    static class RegisterDevice {
        @NotNull(message = "userId is required")
        public Long userId;

        @NotBlank(message = "apnsToken is required")
        public String apnsToken;

        @NotBlank(message = "bundleId is required")
        public String bundleId;

        @NotNull(message = "isSandbox is required")
        public Boolean isSandbox;
    }

    @PostMapping
    public ResponseEntity<Device> register(@Valid @RequestBody RegisterDevice body) {
        if (body == null || body.userId == null || body.apnsToken == null || body.bundleId == null) {
            return ResponseEntity.badRequest().build();
        }

        User u = users.findById(body.userId).orElse(null);
        if (u == null) return ResponseEntity.notFound().build();
        Device d = new Device();
        d.user = u;
        d.platform = "IOS";
        d.apnsToken = body.apnsToken;
        d.bundleId = body.bundleId;
        d.isSandbox = body.isSandbox != null ? body.isSandbox : true;
        d.lastSeenAt = OffsetDateTime.now();

        try {
            Device saved = devices.save(d);
            return ResponseEntity
                    .created(URI.create("/devices/" + saved.id))
                    .body(saved);
        } catch (DataIntegrityViolationException e) {
            // most likely a duplicate apns_token
            return ResponseEntity.status(409).build();
        }
    }

    @GetMapping
    public List<Device> list() { return devices.findAll(); }

    @GetMapping("/{id}")
    public ResponseEntity<Device> get(@PathVariable Long id) {
        Optional<Device> maybeDevice = devices.findById(id);

        if (maybeDevice.isPresent()) {
            Device device = maybeDevice.get();
            return ResponseEntity.ok(device); // 200
        } else {
            return ResponseEntity.notFound().build(); // 404 Not Found
        }
    }
}