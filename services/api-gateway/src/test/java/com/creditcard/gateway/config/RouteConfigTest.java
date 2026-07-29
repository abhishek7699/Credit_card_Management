package com.creditcard.gateway.config;

import com.creditcard.gateway.filter.GrpcTransactionGatewayFilterFactory;
import com.creditcard.transaction.grpc.v1.TransactionServiceGrpc;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.gateway.filter.factory.RequestRateLimiterGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.autoconfigure.exclude=" +
                        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
                "spring.security.oauth2.resourceserver.jwt.jwks-uri=http://localhost:9999/.well-known/jwks.json",
                "REPORTING_SERVICE_URL=http://reporting-service:8086",
                "grpc.client.transaction-service.address=in-process:route-config-test",
                "grpc.client.transaction-service.negotiation-type=PLAINTEXT"
        })
@ActiveProfiles("test")
class RouteConfigTest {

    @Autowired
    private RouteLocator routeLocator;

    @MockBean
    private ReactiveJwtDecoder jwtDecoder;

    @MockBean
    private TransactionServiceGrpc.TransactionServiceFutureStub transactionStub;

    @MockBean
    private RedisRateLimiter redisRateLimiter;

    @MockBean
    private KeyResolver jwtSubjectKeyResolver;

    @MockBean
    private RequestRateLimiterGatewayFilterFactory requestRateLimiterFactory;

    private List<Route> allRoutes() {
        return routeLocator.getRoutes().collectList().block();
    }

    @Test
    void transactionSubmitRouteExists() {
        List<String> ids = allRoutes().stream().map(Route::getId).toList();
        assertThat(ids).contains("transaction-submit");
    }

    @Test
    void transactionGetRouteExists() {
        List<String> ids = allRoutes().stream().map(Route::getId).toList();
        assertThat(ids).contains("transaction-get");
    }

    @Test
    void reportingTransactionsRouteExists() {
        List<String> ids = allRoutes().stream().map(Route::getId).toList();
        assertThat(ids).contains("reporting-transactions");
    }

    @Test
    void reportingMerchantSummaryRouteExists() {
        List<String> ids = allRoutes().stream().map(Route::getId).toList();
        assertThat(ids).contains("reporting-merchant-summary");
    }

    @Test
    void reportingAllMerchantsRouteExists() {
        List<String> ids = allRoutes().stream().map(Route::getId).toList();
        assertThat(ids).contains("reporting-all-merchants");
    }

    @Test
    void reportingSettlementBatchRouteExists() {
        List<String> ids = allRoutes().stream().map(Route::getId).toList();
        assertThat(ids).contains("reporting-settlement-batch");
    }

    @Test
    void reportingMerchantSettlementRouteExists() {
        List<String> ids = allRoutes().stream().map(Route::getId).toList();
        assertThat(ids).contains("reporting-merchant-settlement");
    }
}
