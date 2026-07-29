package com.creditcard.settlement.exception;

public class MerchantSettlementNotFoundException extends RuntimeException {

    private final String merchantId;
    private final String batchId;

    public MerchantSettlementNotFoundException(String merchantId, String batchId) {
        super("No settlement report found for merchantId=" + merchantId + " and batchId=" + batchId);
        this.merchantId = merchantId;
        this.batchId = batchId;
    }

    public String getMerchantId() { return merchantId; }
    public String getBatchId() { return batchId; }
}
