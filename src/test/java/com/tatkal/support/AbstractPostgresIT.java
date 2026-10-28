package com.tatkal.support;

import com.tatkal.config.EmbeddedPostgresLauncher;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for integration tests: one real PostgreSQL for the whole test run (started once,
 * random port) and one shared Spring context. H2 is deliberately not used, because its
 * locking behaves differently and would not prove anything about Postgres.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AbstractPostgresIT.TestClockConfig.class)
public abstract class AbstractPostgresIT {

    static final EmbeddedPostgres PG = EmbeddedPostgresLauncher.start(0);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "postgres");
        r.add("tatkal.sweeper.delay", () -> "1h"); // tests call the sweeper explicitly
    }

    @TestConfiguration
    public static class TestClockConfig {
        @Bean
        @Primary
        public MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}
