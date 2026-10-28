package com.tatkal;

import com.tatkal.config.EmbeddedPostgresLauncher;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class TatkalApplication {

    public static void main(String[] args) {
        // Starts a real PostgreSQL in-process unless an external DB URL was supplied.
        EmbeddedPostgresLauncher.startIfNoExternalDatabase(args);
        SpringApplication.run(TatkalApplication.class, args);
    }
}
