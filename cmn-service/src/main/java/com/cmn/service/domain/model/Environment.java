package com.cmn.service.domain.model;

import java.util.Arrays;
import java.util.Collection;

/**
 * The deployment environment a running instance belongs to.
 *
 * <p>Each constant maps to a Spring profile of the same (lower-case) name and to
 * the OpenBao documents {@code cmn/config/<profile>} and
 * {@code cmn/secret/<profile>}.
 *
 * <p>Domain type: no framework dependencies.
 */
public enum Environment {

    LOCAL("local", false),
    DEV("dev", false),
    STAGING("staging", true),
    PROD("prod", true);

    private final String profile;
    private final boolean secretsRequired;

    Environment(String profile, boolean secretsRequired) {
        this.profile = profile;
        this.secretsRequired = secretsRequired;
    }

    public String profile() {
        return profile;
    }

    /**
     * Whether a missing secret source is a fatal condition. Local and dev may run
     * with placeholder values; staging and prod may not.
     */
    public boolean secretsRequired() {
        return secretsRequired;
    }

    /**
     * Resolve from the set of active Spring profiles.
     *
     * @throws IllegalStateException if none or more than one known environment
     *                               profile is active - an ambiguous deployment
     *                               is a configuration error, not a default
     */
    public static Environment fromActiveProfiles(Collection<String> activeProfiles) {
        var matches = Arrays.stream(values())
                .filter(env -> activeProfiles.contains(env.profile))
                .toList();

        return switch (matches.size()) {
            case 1 -> matches.getFirst();
            case 0 -> throw new IllegalStateException(
                    "No environment profile active. Expected exactly one of "
                            + Arrays.stream(values()).map(Environment::profile).toList()
                            + " but active profiles were " + activeProfiles);
            default -> throw new IllegalStateException(
                    "Ambiguous environment: " + matches.stream().map(Environment::profile).toList()
                            + " are all active. Exactly one is required.");
        };
    }
}
