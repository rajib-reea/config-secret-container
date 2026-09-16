package com.cmn.service.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Pure domain tests: no Spring context, no container, no OpenBao. */
class ConfigurationEntryTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "POSTGRES_PASSWORD",
            "AUTH_MFA_ENCRYPTION_KEY",
            "WORKFLOW_JWT_SECRET",
            "EMAIL_AUTH_CLIENT_SECRET",
            "SUBSCRIPTION_STRIPE_SECRET_KEY",
            "AUTH_JWK_PRIVATE_KEY_PEM",
            "some.api_key.value",
            "lowercase_password"
    })
    @DisplayName("keys naming a secret are masked")
    void masksSensitiveKeys(String key) {
        var entry = entry(key, "super-secret-value");

        assertThat(entry.sensitive()).isTrue();
        assertThat(entry.presentableValue()).isEqualTo("********");
        assertThat(entry.presentableValue()).doesNotContain("super-secret-value");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "POSTGRES_USER",
            "AUTH_ISSUER_URI",
            "SPRING_PROFILES_ACTIVE",
            "SUBSCRIPTION_USAGE_AGGREGATION_CRON",
            "MINIO_ROOT_USER"
    })
    @DisplayName("ordinary configuration keys are shown verbatim")
    void showsNonSensitiveKeys(String key) {
        var entry = entry(key, "plain-value");

        assertThat(entry.sensitive()).isFalse();
        assertThat(entry.presentableValue()).isEqualTo("plain-value");
    }

    @Test
    @DisplayName("an unset secret reports as empty rather than masked")
    void doesNotMaskEmptySecrets() {
        var entry = entry("AUTH_RECAPTCHA_SECRET_KEY", "");

        assertThat(entry.sensitive()).isTrue();
        assertThat(entry.presentableValue()).isEmpty();
        assertThat(entry.length()).isZero();
    }

    @Test
    @DisplayName("length survives masking, so a masked value can still be sanity-checked")
    void reportsLengthOfMaskedValue() {
        var entry = entry("DB_PASSWORD", "hunter2!");

        assertThat(entry.presentableValue()).isEqualTo("********");
        assertThat(entry.length()).isEqualTo(8);
    }

    private static ConfigurationEntry entry(String key, String value) {
        return new ConfigurationEntry(
                new ConfigurationKey(key), value, ConfigurationOrigin.VAULT);
    }
}
