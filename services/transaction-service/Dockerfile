FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser
COPY services/transaction-service/build/libs/*.jar app.jar
RUN chown appuser:appgroup app.jar && mkdir -p logs && chown appuser:appgroup logs
USER appuser
EXPOSE 9090 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
