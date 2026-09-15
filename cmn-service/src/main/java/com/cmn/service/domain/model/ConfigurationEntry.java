package com.cmn.service.domain.model;

import java.util.Objects;

/**
 * One resolved configuration value together with where it came from.
 *
 * <p>A sensitive entry never exposes its raw value through {@link #presentableValue()}.
 * The raw value stays reachable through {@link #rawValue()} for code that genuinely
 * needs it, so that masking is a deliberate act rather than an accident of plumbing.
 *
 * <p>Domain type: no framework dependencies.
 */
public record ConfigurationEntry(ConfigurationKey key, String rawValue, ConfigurationOrigin origin) {

    private static final String MASK = "********";

    public ConfigurationEntry {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(rawValue, "rawValue");
        Objects.requireNonNull(origin, "origin");
    }

    public boolean sensitive() {
        return key.sensitive();
    }

    /**
     * The value as it is safe to display: masked when the key marks it sensitive.
     *
     * <p>An empty value is reported as empty rather than masked - knowing that a
     * secret is <em>unset</em> is operationally useful and reveals nothing.
     */
    public String presentableValue() {
        if (!sensitive()) {
            return rawValue;
        }
        return rawValue.isEmpty() ? "" : MASK;
    }

    /** Length of the underlying value, so a masked entry can still be sanity-checked. */
    public int length() {
        return rawValue.length();
    }
}
