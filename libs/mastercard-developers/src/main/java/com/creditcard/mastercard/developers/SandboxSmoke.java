package com.creditcard.mastercard.developers;

import java.io.PrintStream;
import java.time.LocalDateTime;
import java.util.Map;

/** Manual entry point for scripts/mastercard-sandbox-smoke.sh. Prints the HTTP status and body, nothing secret. */
public final class SandboxSmoke {

    private SandboxSmoke() {}

    public static void main(String[] args) {
        System.exit(run(System.getenv(), System.out, System.err));
    }

    static int run(Map<String, String> env, PrintStream out, PrintStream err) {
        try {
            MastercardDevelopersConfig config = MastercardDevelopersConfig.fromEnvironment(env);
            AccountValidationClient.Result result =
                    new AccountValidationClient(config).validateSandboxPositive(LocalDateTime.now());
            out.println("HTTP " + result.status());
            out.println(result.body());
            return result.status() == 200 ? 0 : 1;
        } catch (Exception e) {
            // Class name and message only. Config messages name variables, never values.
            err.println("Smoke failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return 2;
        }
    }
}
