package com.creditcard.gateway.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Title, description, and the two transaction operations the gateway itself serves
 * (gRPC-backed, so no controller exists to scan). Every other proxied route is documented
 * by its own service's spec. The field names mirror GrpcTransactionGatewayFilterFactory.
 */
@Configuration
public class OpenApiConfig {

    static final String DESCRIPTION =
            "Single entry point: routing, JWT authentication, and rate limiting for the platform APIs. "
            + "Only the two transaction operations are documented here; all other routes are documented by their own services' specs.";

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("API Gateway").description(DESCRIPTION))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .paths(new Paths()
                        .addPathItem("/api/v1/transactions", new PathItem().post(submitTransaction()))
                        .addPathItem("/api/v1/transactions/{id}", new PathItem().get(getTransaction())));
    }

    private static Operation submitTransaction() {
        Schema<Object> body = new Schema<>().type("object")
                .addProperty("cardNumber", new Schema<String>().type("string"))
                .addProperty("cardExpiry", new Schema<String>().type("string").example("12/27"))
                .addProperty("cvv", new Schema<String>().type("string"))
                .addProperty("cardholderName", new Schema<String>().type("string"))
                .addProperty("amount", new Schema<String>().type("string").example("99.99"))
                .addProperty("currencyCode", new Schema<String>().type("string").example("USD"));
        body.setRequired(List.of("cardNumber", "cardholderName", "amount", "currencyCode"));

        Schema<Object> created = new Schema<>().type("object")
                .addProperty("transactionId", new Schema<String>().type("string").format("uuid"))
                .addProperty("status", new Schema<String>().type("string").example("PENDING"));

        return new Operation()
                .summary("Submit a payment")
                .description("Starts a payment for transaction-service. Returns PENDING; the outcome arrives asynchronously. "
                        + "Send the same Idempotency-Key to retry safely: a replay returns the original transactionId and creates no second payment.")
                .addTagsItem("transactions")
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .addParametersItem(new Parameter().name("Idempotency-Key").in("header").required(false)
                        .description("Optional. Unique per payment attempt, e.g. a UUID. Replays with the same key return the original result.")
                        .schema(new Schema<String>().type("string").maxLength(255)))
                .requestBody(new RequestBody().required(true)
                        .content(new Content().addMediaType("application/json", new MediaType().schema(body))))
                .responses(new ApiResponses()
                        .addApiResponse("201", new ApiResponse().description("Payment accepted (PENDING)")
                                .content(new Content().addMediaType("application/json", new MediaType().schema(created))))
                        .addApiResponse("400", new ApiResponse().description("Invalid request body or field"))
                        .addApiResponse("401", new ApiResponse().description("Missing or invalid bearer token")));
    }

    private static Operation getTransaction() {
        Schema<Object> transaction = new Schema<>().type("object")
                .addProperty("transactionId", new Schema<String>().type("string").format("uuid"))
                .addProperty("maskedCardNumber", new Schema<String>().type("string"))
                .addProperty("cardholderName", new Schema<String>().type("string"))
                .addProperty("amount", new Schema<String>().type("string"))
                .addProperty("currencyCode", new Schema<String>().type("string"))
                .addProperty("status", new Schema<String>().type("string"))
                .addProperty("createdAt", new Schema<String>().type("string").format("date-time"))
                .addProperty("updatedAt", new Schema<String>().type("string").format("date-time"));

        return new Operation()
                .summary("Get a payment")
                .addTagsItem("transactions")
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .addParametersItem(new Parameter().name("id").in("path").required(true)
                        .schema(new Schema<String>().type("string").format("uuid")))
                .responses(new ApiResponses()
                        .addApiResponse("200", new ApiResponse().description("The transaction")
                                .content(new Content().addMediaType("application/json", new MediaType().schema(transaction))))
                        .addApiResponse("401", new ApiResponse().description("Missing or invalid bearer token"))
                        .addApiResponse("403", new ApiResponse().description("Not allowed to view this transaction"))
                        .addApiResponse("404", new ApiResponse().description("Transaction not found")));
    }
}
