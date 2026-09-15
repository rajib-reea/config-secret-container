package com.cmn.service.application.service;

import com.cmn.service.domain.model.ConfigurationEntry;
import com.cmn.service.domain.model.ConfigurationSnapshot;
import com.cmn.service.domain.port.in.InspectConfigurationUseCase;
import com.cmn.service.domain.port.out.ConfigurationSourcePort;
import reactor.core.publisher.Mono;

/**
 * Application layer: orchestrates the domain and the driven ports.
 *
 * <p>Deliberately free of Spring annotations. It is a plain class with constructor
 * injection, wired explicitly in
 * {@code infrastructure.config.HexagonalWiringConfiguration}. That keeps the
 * application layer testable with {@code new} and no container, and keeps the
 * framework confined to the infrastructure layer.
 */
public class ConfigurationInspectionService implements InspectConfigurationUseCase {

    private final ConfigurationSourcePort configurationSource;

    public ConfigurationInspectionService(ConfigurationSourcePort configurationSource) {
        this.configurationSource = configurationSource;
    }

    @Override
    public Mono<ConfigurationSnapshot> currentSnapshot() {
        return configurationSource.currentEnvironment()
                .zipWith(configurationSource.loadAll().collectList())
                .map(tuple -> new ConfigurationSnapshot(tuple.getT1(), tuple.getT2()));
    }

    @Override
    public Mono<ConfigurationEntry> findEntry(String key) {
        if (key == null || key.isBlank()) {
            return Mono.error(new IllegalArgumentException("key must not be blank"));
        }
        return configurationSource.findByKey(key);
    }
}
