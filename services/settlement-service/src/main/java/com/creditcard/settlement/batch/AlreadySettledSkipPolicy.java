package com.creditcard.settlement.batch;

import com.creditcard.settlement.exception.AlreadySettledException;
import org.springframework.batch.core.step.skip.SkipPolicy;

public class AlreadySettledSkipPolicy implements SkipPolicy {

    @Override
    public boolean shouldSkip(Throwable t, long skipCount) {
        return t instanceof AlreadySettledException;
    }
}
