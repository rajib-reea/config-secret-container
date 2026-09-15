package com.cmn.service.domain.model;

/**
 * Where a resolved configuration value came from.
 *
 * <p>This is the distinction that matters operationally: a value that came from
 * {@link #OPENBAO} was supplied by the secret store at startup, whereas
 * {@link #LOCAL_FILE} means a bundled default was used instead - which in
 * staging or prod usually signals a misconfiguration.
 *
 * <p>Domain type: no framework dependencies.
 */
public enum ConfigurationOrigin {

    /** Supplied by OpenBao through the Vault config import. */
    OPENBAO,

    /** A bundled application-*.yml or application.yml default. */
    LOCAL_FILE,

    /** An OS environment variable or JVM system property. */
    ENVIRONMENT,

    /** Origin could not be attributed to a known source. */
    UNKNOWN
}
