package com.databasesystem.hospital.demo;

import com.databasesystem.hospital.support.IntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DemoSeedInitializerIntegrationTest extends IntegrationSupport {
    @Autowired PlatformTransactionManager transactions;

    @Test
    void initializesOnlyEmptyDatabaseAndPreservesChangesOnRestart() throws Exception {
        try (var input = new ClassPathResource("db/migration/R__seed_medicine.sql").getInputStream()) {
            jdbc.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        var initializer = new DemoSeedInitializer(jdbc, transactions);
        initializer.run(null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_account", Long.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation", Long.class)).isEqualTo(8);
        jdbc.update("UPDATE staff SET staff_name = '변경 유지' WHERE account_id = (SELECT account_id FROM user_account WHERE login_id = 'admin01')");
        initializer.run(null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation", Long.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT staff_name FROM staff WHERE account_id = (SELECT account_id FROM user_account WHERE login_id = 'admin01')", String.class))
                .isEqualTo("변경 유지");
    }
}
