package com.cmn.service.domain.model;

/**
 * Where a resolved configuration value came from.
 *
 * <p>This is the distinction that matters operationally: a value that came from
 * {@link #VAULT} was supplied by the secret store at startup, whereas
 * {@link #LOCAL_FILE} means a bundled default was used instead - which in
 * staging or prod usually signals a misconfiguration.
 *
 * <p>Domain type: no framework dependencies.
 */
public enum ConfigurationOrigin {

    /**
     * Supplied by the Vault-compatible secret store.
     *
     * <p>Deliberately vendor-neutral. The local stack runs OpenBao, but OpenBao
     * implements the HashiCorp Vault API, and non-local environments are expected
     * to point at whichever of the two that environment runs. Nothing in the
     * service distinguishes them, so neither does this enum.
     */
    VAULT,

    /** A bundled application.yml or application-local.yml default. */
    LOCAL_FILE,

    /** An OS environment variable, JVM system property, or command-line argument. */
    ENVIRONMENT,

    /** Origin could not be attributed to a known source. */
    UNKNOWN
}
