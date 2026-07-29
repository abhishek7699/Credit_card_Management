package com.creditcard.settlement.config;

import com.creditcard.settlement.service.InterchangeFeeService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class BatchConfigTest {

    @Test
    void getInterchangeFeeRate_returnsSetValue() {
        BatchConfig config = new BatchConfig();
        config.setInterchangeFeeRate(new BigDecimal("0.02"));
        assertThat(config.getInterchangeFeeRate()).isEqualByComparingTo("0.02");
    }

    @Test
    void setBatch_roundtrip() {
        BatchConfig config = new BatchConfig();
        BatchConfig.BatchProperties props = new BatchConfig.BatchProperties();
        props.setChunkSize(50);
        props.setCron("0 0 0 * * *");
        config.setBatch(props);

        assertThat(config.getBatch().getChunkSize()).isEqualTo(50);
        assertThat(config.getBatch().getCron()).isEqualTo("0 0 0 * * *");
    }

    @Test
    void batchProperties_defaults() {
        BatchConfig.BatchProperties props = new BatchConfig.BatchProperties();
        assertThat(props.getChunkSize()).isEqualTo(100);
        assertThat(props.getCron()).isEqualTo("0 59 23 * * *");
    }

    @Test
    void interchangeFeeServiceBean_createdWithRate() {
        BatchConfig config = new BatchConfig();
        config.setInterchangeFeeRate(new BigDecimal("0.03"));
        InterchangeFeeService service = config.interchangeFeeService();
        assertThat(service.getInterchangeFeeRate()).isEqualByComparingTo("0.03");
    }
}
