package com.platform.notification.service;
import com.platform.notification.entity.NotificationLog;
import com.platform.notification.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.util.Map;

@Slf4j @Service @RequiredArgsConstructor
public class EmailService {
    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final NotificationLogRepository logRepository;

    @Value("${notification.from:noreply@platform.com}") private String from;

    public void sendTemplated(String to, String subject, String template, Map<String, Object> vars) {
        NotificationLog logEntry = NotificationLog.builder()
                .recipient(to).type("EMAIL").subject(subject).build();
        try {
            Context ctx = new Context();
            vars.forEach(ctx::setVariable);
            String html = templateEngine.process(template, ctx);
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(from); helper.setTo(to);
            helper.setSubject(subject); helper.setText(html, true);
            mailSender.send(msg);
            logEntry.setStatus(NotificationLog.Status.SENT);
            logEntry.setSentAt(Instant.now());
            log.info("Email sent to {}: {}", to, subject);
        } catch (Exception e) {
            logEntry.setStatus(NotificationLog.Status.FAILED);
            logEntry.setErrorMessage(e.getMessage());
            log.error("Email failed to {}: {}", to, e.getMessage());
            throw new RuntimeException("Email send failed", e);
        } finally {
            logRepository.save(logEntry);
        }
    }
}
