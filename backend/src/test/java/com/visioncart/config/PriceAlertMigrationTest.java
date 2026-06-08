package com.visioncart.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PriceAlertMigrationTest {

    @Test
    void activePriceAlertMigrationCleansDuplicatesAndAddsPartialUniqueKey() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V9__add_unique_active_price_alert.sql"));

        assertThat(sql).contains("HAVING COUNT(*) > 1");
        assertThat(sql).contains("SET pa.active = b'0'");
        assertThat(sql).contains("GENERATED ALWAYS AS");
        assertThat(sql).contains("CASE WHEN active = b'1' THEN product_id ELSE NULL END");
        assertThat(sql).contains("UNIQUE KEY uk_price_alert_active_user_product");
    }
}
