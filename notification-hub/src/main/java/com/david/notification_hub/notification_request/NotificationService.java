package com.david.notification_hub.notification_request;

import com.david.notification_hub.device.Device;
import com.david.notification_hub.device.DeviceRepository;
import com.david.notification_hub.delivery_logs.DeliveryLog;
import com.david.notification_hub.delivery_logs.DeliveryLogRepository;
import com.david.notification_hub.notify.Sender;
import com.david.notification_hub.notify.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class NotificationService {

    private final DeviceRepository deviceRepo;
    private final DeliveryLogRepository logRepo;
    private final NotificationRequestRepository requestRepo;
    private final Sender sender;

    public NotificationService(DeviceRepository deviceRepo,
                               DeliveryLogRepository logRepo,
                               NotificationRequestRepository requestRepo,
                               Sender sender) {
        this.deviceRepo = deviceRepo;
        this.logRepo = logRepo;
        this.requestRepo = requestRepo;
        this.sender = sender;
    }

    // Main workflow: figure out targets, send, log, and set status
    @Transactional
    public void process(NotificationRequest req) {
        List<Target> targets = resolveTargets(req);
        if (targets.isEmpty()) {
            req.setStatus("FAILED");
            requestRepo.save(req);
            return;
        }

        boolean anySuccess = false;

        for (Target t : targets) {
            SendResult result = sender.send(t.token, req.getTitle(), req.getBody(), t.isSandbox);

            DeliveryLog log = new DeliveryLog();
            log.setRequest(req);
            if (t.device != null) log.setDevice(t.device);
            log.setAttempt(1);
            log.setSucceeded(result.success);
            if (result.success) {
                log.setApnsResponse(result.response);
            } else {
                log.setErrorMsg(result.error != null ? result.error : "UNKNOWN_ERROR");
            }
            logRepo.save(log);

            if (result.success) anySuccess = true;
        }

        req.setStatus(anySuccess ? "SENT" : "FAILED");
        requestRepo.save(req);
    }

    // Convert a request into concrete targets (tokens)
    private List<Target> resolveTargets(NotificationRequest req) {
        List<Target> out = new ArrayList<>();
        String kind = req.getTargetType();

        if ("USER".equalsIgnoreCase(kind)) {
            try {
                Long userId = Long.parseLong(req.getTargetRef());
                for (Device d : deviceRepo.findByUserId(userId)) {
                    out.add(new Target(d.apnsToken, d.isSandbox, d));
                }
            } catch (NumberFormatException ignored) { /* no targets if not a number */ }
        } else if ("TOKEN".equalsIgnoreCase(kind)) {
            // Direct token target
            out.add(new Target(req.getTargetRef(), true, null));
        }
        return out;
    }

    // Small holder for where to send a single attempt
    private static class Target {
        final String token;
        final boolean isSandbox;
        final Device device;
        Target(String token, boolean isSandbox, Device device) {
            this.token = token; this.isSandbox = isSandbox; this.device = device;
        }
    }
}
