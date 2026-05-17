package com.platform.notification.listener;
import com.platform.common.events.PasswordResetEvent;
import com.platform.common.events.UserRegisteredEvent;
import com.platform.notification.config.RabbitMQConfig;
import com.platform.notification.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import java.util.Map;

@Slf4j @Component @RequiredArgsConstructor
public class NotificationEventListener {
    private final EmailService emailService;

    @RabbitListener(queues = RabbitMQConfig.EMAIL_QUEUE)
    public void handleUserRegistered(UserRegisteredEvent event) {
        log.info("Processing UserRegisteredEvent for {}", event.getEmail());
        emailService.sendTemplated(event.getEmail(), "Welcome to Platform!",
                "welcome", Map.of("fullName", event.getFullName(), "email", event.getEmail()));
    }

    @RabbitListener(queues = RabbitMQConfig.EMAIL_QUEUE)
    public void handlePasswordReset(PasswordResetEvent event) {
        log.info("Processing PasswordResetEvent for {}", event.getEmail());
        emailService.sendTemplated(event.getEmail(), "Password Reset Request",
                "password-reset", Map.of("resetToken", event.getResetToken(), "email", event.getEmail()));
    }
}
