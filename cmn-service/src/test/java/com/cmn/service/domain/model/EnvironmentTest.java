package com.cmn.service.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EnvironmentTest {

    @Test
    @DisplayName("resolves the single active environment profile")
    void resolvesSingleProfile() {
        assertThat(Environment.fromActiveProfiles(List.of("dev"))).isEqualTo(Environment.DEV);
        assertThat(Environment.fromActiveProfiles(List.of("prod", "metrics")))
                .isEqualTo(Environment.PROD);
    }

    @Test
    @DisplayName("refuses to start without an environment profile")
    void rejectsNoProfile() {
        assertThatThrownBy(() -> Environment.fromActiveProfiles(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No environment profile active");
    }

    @Test
    @DisplayName("refuses to start with an ambiguous environment")
    void rejectsAmbiguousProfiles() {
        assertThatThrownBy(() -> Environment.fromActiveProfiles(List.of("dev", "prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ambiguous environment");
    }

    @Test
    @DisplayName("staging and prod require secrets; local and dev do not")
    void secretRequirementsPerEnvironment() {
        assertThat(Environment.LOCAL.secretsRequired()).isFalse();
        assertThat(Environment.DEV.secretsRequired()).isFalse();
        assertThat(Environment.STAGING.secretsRequired()).isTrue();
        assertThat(Environment.PROD.secretsRequired()).isTrue();
    }

    @Test
    @DisplayName("all four profiles exist and map to their lower-case names")
    void allFourProfiles() {
        assertThat(Environment.values())
                .extracting(Environment::profile)
                .containsExactly("local", "dev", "staging", "prod");
    }
}
