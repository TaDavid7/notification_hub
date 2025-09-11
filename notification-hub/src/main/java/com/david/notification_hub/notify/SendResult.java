package com.david.notification_hub.notify;

public class SendResult{
    public final boolean success;
    public final String response;
    public final String error;

    public SendResult(boolean success, String response, String error){
        this.success = success;
        this.response = response;
        this.error = error;
    }
    public static SendResult ok(String response){
        return new SendResult(true, response, null);
    }
    public static SendResult fail(String error){
        return new SendResult(false, null, error);
    }
}