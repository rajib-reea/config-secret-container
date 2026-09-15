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
 * <p>By the time this runs, Spring Cloud Vault has already contributed OpenBao's
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
     * therefore relies primarily on {@link #openBaoSourcePrefixes}, which carries
     * the configured KV backend.
     */
    private static final Set<String> VAULT_SOURCE_MARKERS = Set.of("vault", "openbao");

    private static final Set<String> ENVIRONMENT_SOURCE_NAMES = Set.of(
            "systemEnvironment", "systemProperties");

    private final ConfigurableEnvironment environment;
    private final List<String> includePrefixes;
    private final List<String> openBaoSourcePrefixes;

    /**
     * @param openBaoSourcePrefixes property-source name prefixes that identify OpenBao,
     *                              normally {@code ["cmn/"]} from
     *                              {@code spring.cloud.vault.kv.backend}
     */
    public SpringEnvironmentConfigurationAdapter(
            ConfigurableEnvironment environment,
            List<String> includePrefixes,
            List<String> openBaoSourcePrefixes) {
        this.environment = environment;
        this.includePrefixes = List.copyOf(includePrefixes);
        this.openBaoSourcePrefixes = List.copyOf(openBaoSourcePrefixes);
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
        var entries = new ArrayList<ConfigurationEntry>();
        var seen = new java.util.HashSet<String>();

        // Property sources are ordered by precedence; the first occurrence of a
        // key is the one that actually won, so later duplicates are skipped.
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }

            var origin = originOf(enumerable.getName());

            for (String name : enumerable.getPropertyNames()) {
                if (!seen.add(name) || !included(name, origin)) {
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
     * Everything OpenBao supplied is included. Values from anywhere else are
     * included only when they match a configured prefix, so the endpoint reports
     * this service's configuration rather than the JVM's entire property space.
     */
    private boolean included(String name, ConfigurationOrigin origin) {
        if (origin == ConfigurationOrigin.OPENBAO) {
            return true;
        }
        return includePrefixes.stream().anyMatch(name::startsWith);
    }

    private ConfigurationOrigin originOf(String sourceName) {
        var lower = sourceName.toLowerCase(Locale.ROOT);

        // Primary rule: the source is named after the KV path it was read from.
        if (openBaoSourcePrefixes.stream().anyMatch(sourceName::startsWith)) {
            return ConfigurationOrigin.OPENBAO;
        }
        if (VAULT_SOURCE_MARKERS.stream().anyMatch(lower::contains)) {
            return ConfigurationOrigin.OPENBAO;
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
