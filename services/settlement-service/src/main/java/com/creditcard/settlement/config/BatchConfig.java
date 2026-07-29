package com.creditcard.settlement.config;

import com.creditcard.settlement.service.InterchangeFeeService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

@Configuration
@ConfigurationProperties(prefix = "settlement")
@Validated
public class BatchConfig {

    @NotNull
    @DecimalMin("0.00")
    @DecimalMax("0.50")
    private BigDecimal interchangeFeeRate;

    private BatchProperties batch = new BatchProperties();

    public BigDecimal getInterchangeFeeRate() { return interchangeFeeRate; }
    public void setInterchangeFeeRate(BigDecimal interchangeFeeRate) { this.interchangeFeeRate = interchangeFeeRate; }

    public BatchProperties getBatch() { return batch; }
    public void setBatch(BatchProperties batch) { this.batch = batch; }

    @Bean
    public InterchangeFeeService interchangeFeeService() {
        return new InterchangeFeeService(interchangeFeeRate);
    }

    public static class BatchProperties {
        private int chunkSize = 100;
        private String cron = "0 59 23 * * *";

        public int getChunkSize() { return chunkSize; }
        public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }

        public String getCron() { return cron; }
        public void setCron(String cron) { this.cron = cron; }
    }
}
