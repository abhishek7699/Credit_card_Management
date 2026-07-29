package com.creditcard.settlement.batch;

import com.creditcard.settlement.batch.processor.SettlementItemProcessor;
import com.creditcard.settlement.batch.writer.SettlementItemWriter;
import com.creditcard.settlement.config.BatchConfig;
import com.creditcard.settlement.domain.SettlementTransaction;
import com.creditcard.settlement.kafka.event.TransactionSettledEvent;
import com.creditcard.settlement.model.SettledItem;
import com.creditcard.settlement.repository.MerchantSettlementRepository;
import com.creditcard.settlement.repository.SettlementTransactionRepository;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class SettlementJobConfig {

    @Bean
    public Job settlementJob(JobRepository jobRepository, Step settlementStep) {
        return new JobBuilder("settlementJob", jobRepository)
                .start(settlementStep)
                .build();
    }

    @Bean
    public Step settlementStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JdbcCursorItemReader<SettlementTransaction> settlementTransactionReader,
            SettlementItemProcessor processor,
            SettlementItemWriter settlementItemWriter,
            BatchConfig batchConfig) {
        return new StepBuilder("settlementStep", jobRepository)
                .<SettlementTransaction, SettledItem>chunk(batchConfig.getBatch().getChunkSize(), transactionManager)
                .reader(settlementTransactionReader)
                .processor(processor)
                .writer(settlementItemWriter)
                .faultTolerant()
                .skipPolicy(new AlreadySettledSkipPolicy())
                .build();
    }

    @Bean
    @StepScope
    public SettlementItemWriter settlementItemWriter(
            @Value("#{jobParameters['batchId']}") String batchId,
            SettlementTransactionRepository txRepository,
            MerchantSettlementRepository merchantSettlementRepository,
            KafkaTemplate<String, TransactionSettledEvent> kafkaTemplate) {
        return new SettlementItemWriter(batchId, txRepository, merchantSettlementRepository, kafkaTemplate);
    }
}
