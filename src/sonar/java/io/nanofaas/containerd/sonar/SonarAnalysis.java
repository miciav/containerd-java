package io.nanofaas.containerd.sonar;

import io.nanofaas.containerd.spi.ContainerdClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyses this project with SonarQube, running the server, its database and the scanner as
 * containers driven by containerd-java itself.
 *
 * <p>Usage: {@code ./gradlew sonarAnalysis}. Add {@code -PsonarArgs="--keep"} to leave the stack
 * running afterwards, which is what you want when you intend to browse the results.
 *
 * <p>The containers share the host's network stack, so ports 5432 and 9000 must be free.
 */
public final class SonarAnalysis {

    private static final Logger log = LoggerFactory.getLogger(SonarAnalysis.class);

    private static final String PROJECT_KEY = "io.nanofaas:containerd-java";
    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * A minted analysis token, and the admin password that ended up in force. The password only
     * moves when admin/admin is refused, so reporting it unconditionally would name a password
     * that was never set.
     *
     * @param token the analysis token
     * @param adminPassword the password the admin account actually has now
     */
    private record Credentials(String token, String adminPassword) {
    }

    private SonarAnalysis() {
    }

    public static void main(String[] args) throws Exception {
        var options = java.util.List.of(args);
        boolean keep = options.contains("--keep");
        if (options.contains("--cleanup")) {
            try (ContainerdClient client = ContainerdClient.builder().namespace("nanofaas-sonar").build()) {
                SonarStack.removeLeftovers(client);
            }
            return;
        }
        Path project = Path.of("").toAbsolutePath();
        if (!Files.isDirectory(project.resolve("src/main/java"))) {
            throw new IllegalStateException("run this from the project root; " + project
                    + " has no src/main/java");
        }

        SonarStack.requirePortsFree();

        try (ContainerdClient client = ContainerdClient.builder()
                .namespace("nanofaas-sonar")
                .build()) {

            SonarStack stack = new SonarStack(client);
            String adminPassword = "admin";
            // A shutdown hook as well as the try-with-resources: Ctrl-C during a ten-minute
            // SonarQube start is likely, and it must not leave containers behind.
            Thread cleanup = new Thread(stack::close, "sonar-stack-teardown");
            Runtime.getRuntime().addShutdownHook(cleanup);
            try {
                stack.pullImages();
                stack.startPostgres();
                stack.startSonarQube();

                Credentials credentials = analysisToken(stack);
                stack.authenticateWith(credentials.token());
                adminPassword = credentials.adminPassword();
                int exitCode = stack.runAnalysis(project, credentials.token(), PROJECT_KEY);
                if (exitCode != 0) {
                    throw new IllegalStateException("the analysis exited with " + exitCode);
                }
                stack.awaitComputeEngine(PROJECT_KEY);
                report(stack);
            } finally {
                Runtime.getRuntime().removeShutdownHook(cleanup);
                if (keep) {
                    // Deliberately also on failure: a run that broke is exactly the one worth
                    // poking at. Remove what it left with --cleanup.
                    log.info("--keep: stack left running at {} (admin / {}). Tear it down with"
                                    + " ./gradlew sonarAnalysis -PsonarArgs=--cleanup",
                            stack.sonarQubeUrl(), adminPassword);
                } else {
                    stack.close();
                }
            }
        }
        log.info("done");
    }

    /**
     * Obtains a token the scanner can authenticate with.
     *
     * <p>SonarQube ships with admin/admin and refuses to do anything useful until that password is
     * changed, so the password is changed first and a token minted with the new one.
     */
    private static Credentials analysisToken(SonarStack stack) {
        // A fresh server still accepts admin/admin, so ask for the token first and only move the
        // password if that is refused. Doing it the other way round fails on a server that has
        // already had its password changed by an earlier run against the same volume.
        String name = "containerd-java-" + System.currentTimeMillis();
        String password = "admin";
        var minted = stack.post("/api/user_tokens/generate", "name=" + name + "&type=USER_TOKEN",
                "admin", password);
        if (!minted.ok()) {
            log.info("admin/admin refused ({}), changing the password first", minted);
            var changed = stack.post("/api/users/change_password",
                    "login=admin&previousPassword=admin&password=" + adminPassword(), "admin", "admin");
            if (!changed.ok()) {
                throw new IllegalStateException("could not move the admin password: " + changed);
            }
            password = adminPassword();
            minted = stack.post("/api/user_tokens/generate", "name=" + name + "&type=USER_TOKEN",
                    "admin", password);
        }
        Matcher token = TOKEN.matcher(minted.body() == null ? "" : minted.body());
        if (!token.find()) {
            throw new IllegalStateException("could not mint an analysis token; SonarQube answered " + minted);
        }
        return new Credentials(token.group(1), password);
    }

    /**
     * The password the admin account is moved to. SonarQube rejects keeping "admin", and enforces
     * a policy the value has to satisfy — an all-lowercase one comes back as
     * {@code 400 "Password must contain at least one uppercase character"}.
     */
    private static String adminPassword() {
        return "Containerd-java-analysis-1";
    }

    /** Prints the issue counts and where to browse them. */
    private static void report(SonarStack stack) {
        String issues = stack.get("/api/issues/search?componentKeys=" + PROJECT_KEY
                + "&resolved=false&ps=1&facets=impactSeverities");
        String gate = stack.get("/api/qualitygates/project_status?projectKey=" + PROJECT_KEY);

        log.info("open issues by severity: {}", severities(issues));
        log.info("quality gate: {}", status(gate));
        log.info("browse the results at {}/project/issues?resolved=false&id={}",
                stack.sonarQubeUrl(), PROJECT_KEY);
    }

    private static final Pattern SEVERITY = Pattern.compile(
            "\\{\\s*\"val\"\\s*:\\s*\"([A-Z]+)\"\\s*,\\s*\"count\"\\s*:\\s*(\\d+)");
    private static final Pattern GATE_STATUS = Pattern.compile("\"status\"\\s*:\\s*\"([A-Z]+)\"");

    /** Pulls the severity facet out of the issues response. */
    private static String severities(String body) {
        if (body == null) {
            return "unavailable";
        }
        Matcher m = SEVERITY.matcher(body);
        StringBuilder counts = new StringBuilder();
        while (m.find()) {
            if (!counts.isEmpty()) {
                counts.append(", ");
            }
            counts.append(m.group(1)).append('=').append(m.group(2));
        }
        return counts.isEmpty() ? "none" : counts.toString();
    }

    private static String status(String body) {
        if (body == null) {
            return "unavailable";
        }
        Matcher m = GATE_STATUS.matcher(body);
        return m.find() ? m.group(1) : body;
    }
}
