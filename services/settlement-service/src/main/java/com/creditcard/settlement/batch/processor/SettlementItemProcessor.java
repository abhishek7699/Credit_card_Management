package com.creditcard.settlement.batch.processor;

import com.creditcard.settlement.capture.CaptureGateway;
import com.creditcard.settlement.capture.CaptureResult;
import com.creditcard.settlement.domain.SettlementTransaction;
import com.creditcard.settlement.exception.AlreadySettledException;
import com.creditcard.settlement.model.SettledItem;
import com.creditcard.settlement.service.InterchangeFeeService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class SettlementItemProcessor implements ItemProcessor<SettlementTransaction, SettledItem> {

    private static final Logger log = LoggerFactory.getLogger(SettlementItemProcessor.class);

    private final InterchangeFeeService interchangeFeeService;
    private final CaptureGateway captureGateway;
    private final MeterRegistry meterRegistry;

    public SettlementItemProcessor(InterchangeFeeService interchangeFeeService,
                                   CaptureGateway captureGateway,
                                   MeterRegistry meterRegistry) {
        this.interchangeFeeService = interchangeFeeService;
        this.captureGateway = captureGateway;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Returns null (the item is filtered out, not written) when its capture did not succeed. The transaction stays
     * AUTHORIZED and the next batch run tries it again. Simulator: every capture succeeds, so nothing is filtered.
     */
    @Override
    public SettledItem process(SettlementTransaction transaction) {
        if ("SETTLED".equals(transaction.getStatus())) {
            throw new AlreadySettledException(transaction.getTransactionId().toString());
        }
        CaptureResult capture = captureGateway.capture(
                transaction.getTransactionId(), transaction.getAmount(), transaction.getCurrencyCode());
        if (capture != CaptureResult.CAPTURED) {
            log.warn("Capture {} for txId={}: left AUTHORIZED for the next settlement run",
                    capture, transaction.getTransactionId());
            meterRegistry.counter("settlement.capture.deferred", "outcome", capture.name()).increment();
            return null;
        }
        BigDecimal gross = transaction.getAmount();
        BigDecimal fee = interchangeFeeService.calculateFee(gross);
        BigDecimal net = gross.subtract(fee);
        return new SettledItem(transaction, gross, fee, net);
    }
}
