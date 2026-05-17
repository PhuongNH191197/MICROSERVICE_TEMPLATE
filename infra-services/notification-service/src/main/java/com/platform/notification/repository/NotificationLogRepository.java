package com.platform.notification.repository;
import com.platform.notification.entity.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {}
