package com.creditcard.settlement.batch.reader;

import com.creditcard.settlement.domain.SettlementTransaction;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Configuration
public class AuthorizedTransactionReader {

    @Bean
    @StepScope
    public JdbcCursorItemReader<SettlementTransaction> settlementTransactionReader(DataSource dataSource) {
        JdbcCursorItemReader<SettlementTransaction> reader = new JdbcCursorItemReader<>();
        reader.setDataSource(dataSource);
        reader.setSql("""
                SELECT transaction_id, merchant_id, card_token, masked_card_number, amount,
                       currency_code, status, batch_id, settlement_fee, net_amount,
                       authorized_at, settled_at, created_at
                FROM settlement_transactions
                WHERE status = 'AUTHORIZED'
                ORDER BY authorized_at
                """);
        reader.setRowMapper(AuthorizedTransactionReader::mapRow);
        return reader;
    }

    private static SettlementTransaction mapRow(ResultSet rs, int rowNum) throws SQLException {
        SettlementTransaction tx = new SettlementTransaction();
        tx.setTransactionId(rs.getObject("transaction_id", UUID.class));
        tx.setMerchantId(rs.getString("merchant_id"));
        tx.setCardToken(rs.getString("card_token"));
        tx.setMaskedCardNumber(rs.getString("masked_card_number"));
        tx.setAmount(rs.getBigDecimal("amount"));
        tx.setCurrencyCode(rs.getString("currency_code"));
        tx.setStatus(rs.getString("status"));
        tx.setBatchId(rs.getString("batch_id"));
        tx.setSettlementFee(rs.getBigDecimal("settlement_fee"));
        tx.setNetAmount(rs.getBigDecimal("net_amount"));
        tx.setAuthorizedAt(toInstant(rs.getObject("authorized_at", OffsetDateTime.class)));
        tx.setSettledAt(toInstant(rs.getObject("settled_at", OffsetDateTime.class)));
        tx.setCreatedAt(toInstant(rs.getObject("created_at", OffsetDateTime.class)));
        return tx;
    }

    private static Instant toInstant(OffsetDateTime odt) {
        return Optional.ofNullable(odt).map(OffsetDateTime::toInstant).orElse(null);
    }
}
