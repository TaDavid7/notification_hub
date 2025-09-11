package com.david.notification_hub.notify;

public interface Sender{
    SendResult send(String token, String title, String body, boolean isSandbox);
}