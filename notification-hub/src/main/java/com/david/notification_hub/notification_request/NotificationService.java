package com.david.notification_hub.notification_request;

import com.david.notification_hub.delivery_logs.DeliveryLog;
import com.david.notification_hub.delivery_logs.DeliveryLogRepository;
import com.david.notification_hub.notify.Sender;
import com.david.notification_hub.notify.SendRouter;
import com.david.notification_hub.notify.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class NotificationService {

    private final DeliveryLogRepository logRepo;
    private final NotificationRequestRepository requestRepo;
    private final SendRouter senderRouter;

    public NotificationService(DeliveryLogRepository logRepo,
                               NotificationRequestRepository requestRepo,
                               SendRouter senderRouter) {
        this.logRepo = logRepo;
        this.requestRepo = requestRepo;
        this.senderRouter = senderRouter;

    }

    @Transactional
    public void process(NotificationRequest req) {
        SendResult result = senderRouter.send(
                req.getChannel(),
                "",
                req.getTitle(),
                req.getBody(),
                true
        );

        DeliveryLog log = new DeliveryLog();
        log.setRequest(req);
        log.setAttempt(1);
        log.setSucceeded(result.success);
        if (result.success) {
            log.setProviderResponse(result.response);
        } else {
            log.setErrorMsg(result.error != null ? result.error : "UNKNOWN_ERROR");
        }
        logRepo.save(log);

        req.setStatus(result.success ? "SENT" : "FAILED");
        requestRepo.save(req);
    }
}
