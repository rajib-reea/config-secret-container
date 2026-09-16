package com.cmn.service.domain.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The configuration a running instance resolved, and the environment it resolved it for.
 *
 * <p>Domain type: no framework dependencies.
 */
public record ConfigurationSnapshot(Environment environment, List<ConfigurationEntry> entries) {

    public ConfigurationSnapshot {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(entries, "entries");
        entries = List.copyOf(entries);
    }

    public long secretCount() {
        return entries.stream().filter(ConfigurationEntry::sensitive).count();
    }

    public long configCount() {
        return entries.stream().filter(entry -> !entry.sensitive()).count();
    }

    public Map<ConfigurationOrigin, Long> countByOrigin() {
        return entries.stream()
                .collect(Collectors.groupingBy(ConfigurationEntry::origin, Collectors.counting()));
    }

    /** Whether any value at all was supplied by Vault. */
    public boolean backedByVault() {
        return entries.stream().anyMatch(entry -> entry.origin() == ConfigurationOrigin.VAULT);
    }

    /**
     * Whether this snapshot is safe to run with.
     *
     * <p>An environment that requires secrets must have received at least one value
     * from Vault; otherwise the instance is running on bundled defaults and should
     * not be serving traffic.
     */
    public boolean satisfiesEnvironmentRequirements() {
        return !environment.secretsRequired() || backedByVault();
    }
}
