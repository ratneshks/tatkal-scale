package com.tatkal.config;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;

/**
 * Starts a real PostgreSQL server (binaries come from a Maven dependency) so the project
 * runs with one command and no Docker. Benchmarks need real Postgres row locks, MVCC and
 * SKIP LOCKED; H2 behaves differently and would not prove anything.
 *
 * Skipped when the user supplies their own database via SPRING_DATASOURCE_URL,
 * -Dspring.datasource.url or --spring.datasource.url.
 */
public final class EmbeddedPostgresLauncher {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedPostgresLauncher.class);
    private static final int DEFAULT_PORT = 54329;

    private EmbeddedPostgresLauncher() {
    }

    public static void startIfNoExternalDatabase(String[] args) {
        // Check the web port first: if another copy of the app is already running, say so now
        // instead of after 20 seconds of database startup.
        int webPort = webPort(args);
        if (isPortInUse(webPort)) {
            log.error("""

                    *************************************************************
                    Port {} is already in use. Another copy of Tatkal-Scale (or
                    another program) is probably still running.
                    Stop it with Ctrl+C in its terminal, or start this copy on a
                    different port:  mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
                    *************************************************************""", webPort);
            System.exit(1);
        }

        boolean external = System.getProperty("spring.datasource.url") != null
                || System.getenv("SPRING_DATASOURCE_URL") != null
                || Arrays.stream(args).anyMatch(a -> a.startsWith("--spring.datasource.url"));
        if (external) {
            log.info("External database configured; embedded PostgreSQL not started");
            return;
        }
        int port = Integer.parseInt(System.getenv().getOrDefault("TATKAL_EMBEDDED_PG_PORT", String.valueOf(DEFAULT_PORT)));
        if (isPortInUse(port)) {
            // Never share another copy's database by accident: take a free port instead.
            log.warn("Port {} is busy (another copy running?). Starting this copy's own database on a free port.", port);
            port = 0;
        }
        EmbeddedPostgres pg = start(port);
        port = pg.getPort();
        System.setProperty("spring.datasource.url", pg.getJdbcUrl("postgres", "postgres"));
        System.setProperty("spring.datasource.username", "postgres");
        System.setProperty("spring.datasource.password", "postgres");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                pg.close();
            } catch (IOException ignored) {
                // shutting down anyway
            }
        }, "embedded-pg-shutdown"));
        log.info("Embedded PostgreSQL started on port {}", port);
    }

    /** The port Tomcat will use: --server.port=..., -Dserver.port, SERVER_PORT, else 8080. */
    private static int webPort(String[] args) {
        String value = Arrays.stream(args)
                .filter(a -> a.startsWith("--server.port="))
                .map(a -> a.substring("--server.port=".length()))
                .findFirst()
                .orElse(System.getProperty("server.port", System.getenv().getOrDefault("SERVER_PORT", "8080")));
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 8080;
        }
    }

    /** True if something already accepts connections on localhost:port. */
    static boolean isPortInUse(int port) {
        if (port <= 0) {
            return false;
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static EmbeddedPostgres start(int port) {
        try {
            EmbeddedPostgres.Builder builder = EmbeddedPostgres.builder()
                    // a bigger connection limit so pool-size experiments are possible
                    .setServerConfig("max_connections", "300");
            if (port > 0) {
                builder.setPort(port);
            }
            return builder.start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start embedded PostgreSQL", e);
        }
    }
}
