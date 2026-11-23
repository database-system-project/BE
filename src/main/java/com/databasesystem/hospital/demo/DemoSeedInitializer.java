package com.databasesystem.hospital.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;

/** Opt-in initialization of a fresh Render demonstration database. */
@Component
@Profile("render")
@ConditionalOnProperty(name = "hospital.demo.seed-on-start", havingValue = "true")
public class DemoSeedInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoSeedInitializer.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public DemoSeedInitializer(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String sql;
        try (var input = new ClassPathResource("demo/initial-data.sql").getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("(?m)^BEGIN;\\s*$", "")
                    .replaceAll("(?m)^COMMIT;\\s*$", "");
        }
        String seed = sql;
        Boolean applied = transaction.execute(status -> {
            jdbc.execute("LOCK TABLE user_account IN EXCLUSIVE MODE");
            if (jdbc.queryForObject("SELECT count(*) FROM user_account", Long.class) > 0) {
                return false;
            }
            jdbc.execute(seed);
            return true;
        });
        log.info(Boolean.TRUE.equals(applied)
                ? "Initialized fresh demonstration database"
                : "Preserved existing demonstration database");
    }
}
