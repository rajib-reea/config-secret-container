package com.cmn.service.domain.model;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The name of a configuration value, and whether that name marks it as sensitive.
 *
 * <p>The classification rule is deliberately identical to the one in
 * {@code openbao/env-to-bao.sh}, so a value routed to {@code cmn/secret/<env>}
 * during migration is also the value this service refuses to echo back in
 * clear text.
 *
 * <p>Domain type: no framework dependencies.
 */
public record ConfigurationKey(String value) implements Comparable<ConfigurationKey> {

    /** Name fragments that mark a value as sensitive. Mirrors env-to-bao.sh. */
    private static final List<String> SENSITIVE_MARKERS = List.of(
            "PASSWORD",
            "PASSWD",
            "PWD",
            "SECRET",
            "TOKEN",
            "PRIVATE_KEY",
            "ENCRYPTION_KEY",
            "API_KEY",
            "ACCESS_KEY",
            "CLIENT_SECRET",
            "CREDENTIAL");

    public ConfigurationKey {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Configuration key must not be blank");
        }
    }

    /**
     * Whether this key names a secret. Matching is case-insensitive and on
     * substrings, so {@code AUTH_POSTGRES_PASSWORD} and {@code jwtSecret} both
     * qualify.
     */
    public boolean sensitive() {
        var upper = value.toUpperCase(Locale.ROOT);
        return SENSITIVE_MARKERS.stream().anyMatch(upper::contains);
    }

    @Override
    public int compareTo(ConfigurationKey other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
