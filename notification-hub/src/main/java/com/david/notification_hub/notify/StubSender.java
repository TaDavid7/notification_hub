package com.david.notification_hub.notify;

import org.springframework.stereotype.Component;

@Component
public class StubSender implements Sender{
    @Override
    public SendResult send(String token, String title, String body, boolean isSandbox){
        return SendResult.ok("STUB_DELIVERED");
    }
}