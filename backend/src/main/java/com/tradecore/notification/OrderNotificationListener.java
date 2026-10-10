package com.tradecore.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class OrderNotificationListener {
    private static final Logger log = LoggerFactory.getLogger(OrderNotificationListener.class);
    private final NotificationService notifications;

    public OrderNotificationListener(NotificationService notifications) { this.notifications = notifications; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderNotification(OrderNotificationEvent event) {
        try {
            notifications.createIfAbsent(event.email(), event.type(), event.title(), event.message());
        } catch (RuntimeException failure) {
            log.warn("Order notification could not be persisted type={} category={}", event.type(), failure.getClass().getSimpleName());
        }
    }
}
