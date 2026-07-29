package com.creditcard.notification.repository;

import com.creditcard.notification.domain.NotificationRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NotificationRecordRepository extends JpaRepository<NotificationRecord, UUID> {
}
