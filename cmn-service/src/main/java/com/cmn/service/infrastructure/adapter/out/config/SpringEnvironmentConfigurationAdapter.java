package com.cmn.service.infrastructure.adapter.out.config;

import com.cmn.service.domain.model.ConfigurationEntry;
import com.cmn.service.domain.model.ConfigurationKey;
import com.cmn.service.domain.model.ConfigurationOrigin;
import com.cmn.service.domain.model.Environment;
import com.cmn.service.domain.port.out.ConfigurationSourcePort;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Driven adapter: implements {@link ConfigurationSourcePort} over Spring's
 * {@link ConfigurableEnvironment}.
 *
 * <p>By the time this runs, Spring Cloud Vault has already contributed the store's
 * {@code cmn/config/<env>} and {@code cmn/secret/<env>} documents as property
 * sources. This adapter reads them back out and attributes each value to its origin.
 *
 * <p>This is the only class in the service that knows Spring exists on the
 * configuration side. The domain and application layers see only the port.
 */
public class SpringEnvironmentConfigurationAdapter implements ConfigurationSourcePort {

    /**
     * Fallback markers for property sources that name themselves after Vault.
     *
     * <p>These are a safety net only. Spring Cloud Vault's config-import names each
     * property source after the <em>path it read</em> - {@code cmn/config/dev},
     * {@code cmn/secret/dev} - with no mention of "vault" anywhere. Attribution
     * therefore relies primarily on {@link #vaultSourcePrefixes}, which carries
     * the configured KV backend.
     */
    private static final Set<String> VAULT_SOURCE_MARKERS = Set.of("vault", "openbao");

    /**
     * Sources that represent an external override rather than a file or the store.
     * {@code commandLineArgs} belongs here: a value passed with {@code --KEY=...}
     * outranks Vault, and reporting it as UNKNOWN would obscure that.
     */
    private static final Set<String> ENVIRONMENT_SOURCE_NAMES = Set.of(
            "systemEnvironment", "systemProperties", "commandLineArgs");

    private final ConfigurableEnvironment environment;
    private final List<String> includePrefixes;
    private final List<String> includeSources;
    private final List<String> vaultSourcePrefixes;

    /**
     * @param includeSources      property-source name fragments whose values are all
     *                            reported. The local profile names
     *                            {@code application-local.yml} here, because on that
     *                            profile nothing comes from Vault and the report would
     *                            otherwise be almost empty
     * @param vaultSourcePrefixes property-source name prefixes that identify Vault,
     *                            normally {@code ["cmn/"]} from
     *                            {@code spring.cloud.vault.kv.backend}
     */
    public SpringEnvironmentConfigurationAdapter(
            ConfigurableEnvironment environment,
            List<String> includePrefixes,
            List<String> includeSources,
            List<String> vaultSourcePrefixes) {
        this.environment = environment;
        this.includePrefixes = List.copyOf(includePrefixes);
        this.includeSources = List.copyOf(includeSources);
        this.vaultSourcePrefixes = List.copyOf(vaultSourcePrefixes);
    }

    @Override
    public Flux<ConfigurationEntry> loadAll() {
        return Flux.fromIterable(collectEntries());
    }

    @Override
    public Mono<ConfigurationEntry> findByKey(String key) {
        return Mono.justOrEmpty(
                collectEntries().stream()
                        .filter(entry -> entry.key().value().equals(key))
                        .findFirst());
    }

    @Override
    public Mono<Environment> currentEnvironment() {
        return Mono.fromSupplier(
                () -> Environment.fromActiveProfiles(List.of(environment.getActiveProfiles())));
    }

    // ------------------------------------------------------------------

    private List<ConfigurationEntry> collectEntries() {
        // Names defined by Vault anywhere in the stack, at any precedence.
        //
        // This is collected separately, and deliberately: a key can be defined by
        // Vault and then overridden by a command-line argument or environment
        // variable. Deciding inclusion from the winning source alone would drop
        // such a key from the report entirely - which hides exactly the case an
        // operator most needs to see, a Vault-managed value being overridden.
        var vaultNames = new java.util.HashSet<String>();

        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable
                    && originOf(enumerable.getName()) == ConfigurationOrigin.VAULT) {
                vaultNames.addAll(List.of(enumerable.getPropertyNames()));
            }
        }

        // Names defined by an explicitly included source - on the local profile,
        // everything application-local.yml declares. Collected the same way and
        // for the same reason as vaultNames above: inclusion must not depend on
        // which source ultimately won.
        var includedSourceNames = new java.util.HashSet<String>();

        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable
                    && matchesIncludedSource(enumerable.getName())) {
                includedSourceNames.addAll(List.of(enumerable.getPropertyNames()));
            }
        }

        var entries = new ArrayList<ConfigurationEntry>();
        var seen = new java.util.HashSet<String>();

        // Property sources are ordered by precedence; the first occurrence of a
        // key is the source that actually won, and therefore its true origin.
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }

            var origin = originOf(enumerable.getName());

            for (String name : enumerable.getPropertyNames()) {
                if (!included(name, vaultNames, includedSourceNames) || !seen.add(name)) {
                    continue;
                }

                var raw = environment.getProperty(name);
                if (raw == null) {
                    continue;
                }

                entries.add(new ConfigurationEntry(new ConfigurationKey(name), raw, origin));
            }
        }

        entries.sort((left, right) -> left.key().compareTo(right.key()));
        return entries;
    }

    /**
     * Any name Vault defines is reported, whatever ultimately supplied its value.
     * Names from anywhere else are reported only when they match a configured
     * prefix, so the endpoint describes this service's configuration rather than
     * the JVM's entire property space.
     */
    private boolean included(
            String name, java.util.Set<String> vaultNames, java.util.Set<String> sourceNames) {
        if (vaultNames.contains(name) || sourceNames.contains(name)) {
            return true;
        }
        return includePrefixes.stream().anyMatch(name::startsWith);
    }

    /** Whether a property source was explicitly named in {@code cmn.inspection.include-sources}. */
    private boolean matchesIncludedSource(String sourceName) {
        return includeSources.stream().anyMatch(sourceName::contains);
    }

    private ConfigurationOrigin originOf(String sourceName) {
        var lower = sourceName.toLowerCase(Locale.ROOT);

        // Primary rule: the source is named after the KV path it was read from.
        if (vaultSourcePrefixes.stream().anyMatch(sourceName::startsWith)) {
            return ConfigurationOrigin.VAULT;
        }
        if (VAULT_SOURCE_MARKERS.stream().anyMatch(lower::contains)) {
            return ConfigurationOrigin.VAULT;
        }
        if (ENVIRONMENT_SOURCE_NAMES.contains(sourceName)) {
            return ConfigurationOrigin.ENVIRONMENT;
        }
        if (lower.contains("application") || lower.contains("class path resource")) {
            return ConfigurationOrigin.LOCAL_FILE;
        }
        return ConfigurationOrigin.UNKNOWN;
    }
}
