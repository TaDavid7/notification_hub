package com.david.pingr.notify;

public interface Sender{
    SendResult send(String token, String title, String body, boolean isSandbox);
}