package com.tradecore.notification;

public record OrderNotificationEvent(String email, String type, String title, String message) { }
