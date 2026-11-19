package com.creditcard.gateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    private final OpenAPI api = new OpenApiConfig().openApi();

    @Test
    void titleAndDescriptionIdentifyTheServiceAndScope() {
        assertThat(api.getInfo().getTitle()).isEqualTo("API Gateway");
        assertThat(api.getInfo().getDescription()).contains("Only the two transaction operations are documented here");
    }

    @Test
    void documentsOnlyTheSubmitAndGetTransactionOperations() {
        assertThat(api.getPaths().keySet()).containsExactlyInAnyOrder("/api/v1/transactions", "/api/v1/transactions/{id}");

        PathItem submit = api.getPaths().get("/api/v1/transactions");
        assertThat(submit.getPost().getRequestBody().getContent().get("application/json").getSchema().getRequired())
                .containsExactlyInAnyOrder("cardNumber", "cardholderName", "amount", "currencyCode");
        assertThat(submit.getPost().getResponses()).containsKeys("201", "400", "401");
        assertThat(submit.getPost().getParameters()).singleElement().satisfies(p -> {
            assertThat(p.getName()).isEqualTo("Idempotency-Key");
            assertThat(p.getIn()).isEqualTo("header");
            assertThat(p.getRequired()).isFalse();
        });

        PathItem get = api.getPaths().get("/api/v1/transactions/{id}");
        assertThat(get.getGet().getResponses()).containsKeys("200", "401", "403", "404");
        assertThat(get.getGet().getParameters()).hasSize(1);
    }

    @Test
    void everyOperationRequiresABearerToken() {
        assertThat(api.getComponents().getSecuritySchemes().get("bearerAuth").getScheme()).isEqualTo("bearer");
        assertThat(api.getPaths().get("/api/v1/transactions").getPost().getSecurity()).hasSize(1);
        assertThat(api.getPaths().get("/api/v1/transactions/{id}").getGet().getSecurity()).hasSize(1);
    }
}
