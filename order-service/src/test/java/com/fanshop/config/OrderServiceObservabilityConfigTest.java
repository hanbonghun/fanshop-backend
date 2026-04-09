package com.fanshop.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestConstructor;

@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class OrderServiceObservabilityConfigTest {

    private final Environment environment;

    OrderServiceObservabilityConfigTest(Environment environment) {
        this.environment = environment;
    }

    @Test
    @DisplayName("order-service는 로컬에서 OTLP metrics export를 비활성화한다")
    void disableOtlpMetricsExport() {
        assertThat(environment.getProperty("management.otlp.metrics.export.enabled", Boolean.class)).isFalse();
    }

}
