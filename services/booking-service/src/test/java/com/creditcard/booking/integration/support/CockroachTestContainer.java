package com.creditcard.booking.integration.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * No official Testcontainers module for CockroachDB exists under this project's
 * Testcontainers 2.x line (org.testcontainers:cockroachdb was only ever published up
 * to the old 1.x series) — a plain GenericContainer using the exact image and startup
 * command docker-compose.yml's own cockroachdb service uses is the correct, minimal
 * fit, and is what actually lets tests catch CockroachDB-specific schema quirks (e.g.
 * a bare INT column defaulting to 8-byte INT8, unlike Postgres's 4-byte INT) that a
 * generic Postgres container never would — see the INT4 fix this replaces the
 * Postgres-backed test setup to catch.
 */
public final class CockroachTestContainer {

    private static final String IMAGE = "cockroachdb/cockroach:v23.2.0";
    private static final int SQL_PORT = 26257;
    private static final int HTTP_PORT = 8080;

    private CockroachTestContainer() {
    }

    public static GenericContainer<?> newContainer() {
        return new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withCommand("start-single-node", "--insecure", "--store=type=mem,size=640MiB")
                .withExposedPorts(SQL_PORT, HTTP_PORT)
                .waitingFor(Wait.forHttp("/health?ready=1")
                        .forPort(HTTP_PORT)
                        .withStartupTimeout(Duration.ofSeconds(60)));
    }

    /** CockroachDB speaks the Postgres wire protocol — the existing org.postgresql
     *  driver already on the classpath connects to it with no change. */
    public static String jdbcUrl(GenericContainer<?> container) {
        return "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(SQL_PORT)
                + "/defaultdb?sslmode=disable";
    }
}
