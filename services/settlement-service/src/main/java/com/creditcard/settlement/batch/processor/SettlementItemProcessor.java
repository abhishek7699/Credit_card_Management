package com.creditcard.settlement.batch.processor;

import com.creditcard.settlement.domain.SettlementTransaction;
import com.creditcard.settlement.exception.AlreadySettledException;
import com.creditcard.settlement.model.SettledItem;
import com.creditcard.settlement.service.InterchangeFeeService;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class SettlementItemProcessor implements ItemProcessor<SettlementTransaction, SettledItem> {

    private final InterchangeFeeService interchangeFeeService;

    public SettlementItemProcessor(InterchangeFeeService interchangeFeeService) {
        this.interchangeFeeService = interchangeFeeService;
    }

    @Override
    public SettledItem process(SettlementTransaction transaction) {
        if ("SETTLED".equals(transaction.getStatus())) {
            throw new AlreadySettledException(transaction.getTransactionId().toString());
        }
        BigDecimal gross = transaction.getAmount();
        BigDecimal fee = interchangeFeeService.calculateFee(gross);
        BigDecimal net = gross.subtract(fee);
        return new SettledItem(transaction, gross, fee, net);
    }
}
