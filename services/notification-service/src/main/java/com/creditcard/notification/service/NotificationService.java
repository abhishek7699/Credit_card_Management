package com.creditcard.notification.service;

import com.creditcard.notification.domain.CardholderContact;
import com.creditcard.notification.domain.NotificationRecord;
import com.creditcard.notification.kafka.event.FraudAlertEvent;
import com.creditcard.notification.kafka.event.TransactionStatusEvent;
import com.creditcard.notification.repository.CardholderContactRepository;
import com.creditcard.notification.repository.NotificationRecordRepository;
import com.creditcard.notification.repository.ProcessedEventRepository;
import com.creditcard.notification.service.channel.NotificationChannelGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final CardholderContactRepository contactRepo;
    private final NotificationRecordRepository recordRepo;
    private final ProcessedEventRepository processedEventRepo;
    private final List<NotificationChannelGateway> gateways;

    public NotificationService(CardholderContactRepository contactRepo,
                                NotificationRecordRepository recordRepo,
                                ProcessedEventRepository processedEventRepo,
                                List<NotificationChannelGateway> gateways) {
        this.contactRepo = contactRepo;
        this.recordRepo = recordRepo;
        this.processedEventRepo = processedEventRepo;
        this.gateways = gateways;
    }

    @Transactional
    public void processTransactionEvent(TransactionStatusEvent event) {
        if (event.getEventId() == null || event.getTransactionId() == null) {
            log.warn("Discarding transaction event with null eventId or transactionId");
            return;
        }

        String key = "notif:" + event.getEventType() + ":" + event.getEventId();
        int inserted = processedEventRepo.insertIfAbsent(key);
        if (inserted == 0) {
            log.debug("Duplicate transaction event, skipping: {}", key);
            return;
        }

        Optional<CardholderContact> contactOpt = contactRepo.findById(event.getCardToken());
        if (contactOpt.isEmpty()) {
            log.warn("No CardholderContact found for cardToken, skipping notification");
            return;
        }

        CardholderContact contact = contactOpt.get();
        if (!contact.isTransactionNotificationsEnabled()) {
            log.debug("Transaction notifications disabled for card token, skipping");
            return;
        }

        String last4 = last4(contact.getMaskedCardNumber());
        String subject = buildTransactionSubject(event.getEventType());
        String body = buildTransactionBody(event.getEventType(), last4, event.getTransactionId(), event.getReasonCode());

        List<NotificationRecord> records = buildPendingRecords(event.getEventId(), event.getEventType(), contact, subject, body);
        recordRepo.saveAll(records);
        dispatchRecords(records, contact, subject, body);
    }

    @Transactional
    public void processFraudAlertEvent(FraudAlertEvent event) {
        if (event.getEventId() == null || event.getTransactionId() == null) {
            log.warn("Discarding fraud alert event with null eventId or transactionId");
            return;
        }

        String key = "notif:FraudAlertRaised:" + event.getEventId();
        int inserted = processedEventRepo.insertIfAbsent(key);
        if (inserted == 0) {
            log.debug("Duplicate fraud alert event, skipping: {}", key);
            return;
        }

        Optional<CardholderContact> contactOpt = contactRepo.findById(event.getCardToken());
        if (contactOpt.isEmpty()) {
            log.warn("No CardholderContact found for cardToken in fraud alert, skipping");
            return;
        }

        CardholderContact contact = contactOpt.get();
        String last4 = last4(contact.getMaskedCardNumber());
        String subject = buildFraudAlertSubject();

        List<NotificationRecord> records = new ArrayList<>();
        for (NotificationChannelGateway gateway : gateways) {
            String recipientRaw = channelRecipient(gateway.channel(), contact);
            if (recipientRaw == null) continue;

            String body = gateway.channel() == NotificationChannelGateway.NotificationChannel.EMAIL
                    ? buildFraudAlertEmailBody(last4, event.getTriggeredRuleTypes(), event.getAlertId())
                    : buildFraudAlertSmsBody(last4, event.getTriggeredRuleTypes());

            NotificationRecord record = new NotificationRecord();
            record.setEventId(event.getEventId());
            record.setEventType("FraudAlertRaised");
            record.setChannel(gateway.channel().name());
            record.setRecipientMasked(maskRecipient(gateway.channel(), recipientRaw));
            record.setContent(body);
            records.add(record);
        }

        recordRepo.saveAll(records);
        dispatchFraudRecords(records, contact, subject, event.getTriggeredRuleTypes(), last4, event.getAlertId());
    }

    private List<NotificationRecord> buildPendingRecords(String eventId, String eventType,
                                                          CardholderContact contact,
                                                          String subject, String body) {
        List<NotificationRecord> records = new ArrayList<>();
        for (NotificationChannelGateway gateway : gateways) {
            String recipientRaw = channelRecipient(gateway.channel(), contact);
            if (recipientRaw == null) continue;

            NotificationRecord record = new NotificationRecord();
            record.setEventId(eventId);
            record.setEventType(eventType);
            record.setChannel(gateway.channel().name());
            record.setRecipientMasked(maskRecipient(gateway.channel(), recipientRaw));
            record.setContent(body);
            records.add(record);
        }
        return records;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateRecordStatus(NotificationRecord record, String status, String failureReason) {
        record.setStatus(status);
        record.setFailureReason(failureReason);
        if ("SENT".equals(status)) {
            record.setSentAt(Instant.now());
        }
        recordRepo.save(record);
    }

    private void dispatchRecords(List<NotificationRecord> records, CardholderContact contact,
                                  String subject, String body) {
        for (NotificationRecord record : records) {
            String recipient = channelRecipient(
                    NotificationChannelGateway.NotificationChannel.valueOf(record.getChannel()), contact);
            dispatchSingle(record, recipient, subject, body);
        }
    }

    private void dispatchFraudRecords(List<NotificationRecord> records, CardholderContact contact,
                                       String subject, List<String> ruleTypes, String last4, String alertId) {
        for (NotificationRecord record : records) {
            NotificationChannelGateway.NotificationChannel ch =
                    NotificationChannelGateway.NotificationChannel.valueOf(record.getChannel());
            String recipient = channelRecipient(ch, contact);
            String body = ch == NotificationChannelGateway.NotificationChannel.EMAIL
                    ? buildFraudAlertEmailBody(last4, ruleTypes, alertId)
                    : buildFraudAlertSmsBody(last4, ruleTypes);
            dispatchSingle(record, recipient, subject, body);
        }
    }

    private void dispatchSingle(NotificationRecord record, String recipient, String subject, String body) {
        NotificationChannelGateway.NotificationChannel ch =
                NotificationChannelGateway.NotificationChannel.valueOf(record.getChannel());
        try {
            NotificationChannelGateway gateway = gateways.stream()
                    .filter(g -> g.channel() == ch)
                    .findFirst()
                    .orElseThrow();
            gateway.send(recipient, subject, body);
            updateRecordStatus(record, "SENT", null);
        } catch (Exception e) {
            log.warn("Channel send failed for channel {}: {}", ch, e.getMessage());
            updateRecordStatus(record, "FAILED", e.getMessage());
        }
    }

    private String channelRecipient(NotificationChannelGateway.NotificationChannel channel,
                                     CardholderContact contact) {
        return switch (channel) {
            case EMAIL -> contact.getEmail();
            case SMS -> contact.getPhoneNumber();
        };
    }

    private String maskRecipient(NotificationChannelGateway.NotificationChannel channel, String raw) {
        return switch (channel) {
            case EMAIL -> maskEmail(raw);
            case SMS -> maskPhone(raw);
        };
    }

    public static String maskEmail(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) return "***";
        return email.charAt(0) + "***" + email.substring(atIndex);
    }

    public static String maskPhone(String phone) {
        String digits = phone.replaceAll("[^\\d]", "");
        if (digits.length() < 4) return "***-***-" + digits;
        String last4 = digits.substring(digits.length() - 4);
        return "***-***-" + last4;
    }

    public static String last4(String maskedCardNumber) {
        if (maskedCardNumber == null || maskedCardNumber.length() < 4) return maskedCardNumber;
        return maskedCardNumber.substring(maskedCardNumber.length() - 4);
    }

    public static String buildTransactionSubject(String eventType) {
        return switch (eventType) {
            case "TransactionAuthorized" -> "Payment Approved";
            case "TransactionDeclined"   -> "Payment Declined";
            default                       -> "Payment Failed";
        };
    }

    public static String buildTransactionBody(String eventType, String last4, String transactionId, String reasonCode) {
        return switch (eventType) {
            case "TransactionAuthorized" ->
                    "Payment approved: Card ending in " + last4 + ". Reference: " + transactionId + ".";
            case "TransactionDeclined" ->
                    "Payment declined: Card ending in " + last4 + ". Reason: " + reasonCode + ". Reference: " + transactionId + ".";
            default ->
                    "Payment failed: Card ending in " + last4 + ". Reason: " + reasonCode + ". Reference: " + transactionId + ".";
        };
    }

    public static String buildFraudAlertSubject() {
        return "URGENT: Suspicious Activity Detected";
    }

    public static String buildFraudAlertEmailBody(String last4, List<String> ruleTypes, String alertId) {
        String rules = String.join(", ", ruleTypes);
        return "Suspicious activity detected on card ending in " + last4 + ". " +
               "Triggered by: " + rules + ". Alert ID: " + alertId + ". " +
               "Contact us immediately if this was not you.";
    }

    public static String buildFraudAlertSmsBody(String last4, List<String> ruleTypes) {
        String rules = String.join(", ", ruleTypes);
        return "ALERT: Suspicious activity on card ending in " + last4 + ". " +
               "Triggered by: " + rules + ". Contact us if not you.";
    }
}
