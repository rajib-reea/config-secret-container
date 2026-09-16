package com.cmn.service.infrastructure.config;

import com.cmn.service.application.service.ConfigurationInspectionService;
import com.cmn.service.domain.port.in.InspectConfigurationUseCase;
import com.cmn.service.domain.port.out.ConfigurationSourcePort;
import com.cmn.service.infrastructure.adapter.out.config.SpringEnvironmentConfigurationAdapter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * The composition root.
 *
 * <p>Every dependency arrow in this service points inward: infrastructure depends
 * on application, application depends on domain, and the domain depends on nothing.
 * The only place those layers are joined is here, which is why the domain and
 * application packages contain no Spring annotations at all.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CmnServiceProperties.class)
public class HexagonalWiringConfiguration {

    /** Driven side: OpenBao-backed configuration, read through Spring's Environment. */
    @Bean
    ConfigurationSourcePort configurationSourcePort(
            ConfigurableEnvironment environment, CmnServiceProperties properties) {

        // Spring Cloud Vault names each property source after the KV path it read
        // ("cmn/config/dev"), never after "vault". Derive the prefix from the
        // configured backend so values can be attributed to OpenBao.
        var backend = environment.getProperty("spring.cloud.vault.kv.backend", "cmn");

        return new SpringEnvironmentConfigurationAdapter(
                environment,
                properties.inspection().includePrefixes(),
                java.util.List.of(backend + "/"));
    }

    /** Driving side: the use case the web adapter calls. */
    @Bean
    InspectConfigurationUseCase inspectConfigurationUseCase(
            ConfigurationSourcePort configurationSourcePort) {
        return new ConfigurationInspectionService(configurationSourcePort);
    }

    /**
     * Startup gate for environments that require secrets.
     *
     * <p>Spring Cloud Vault treats a missing KV path as empty rather than as an
     * error, so staging and prod would otherwise start on bundled defaults.
     */
    @Bean
    SecretsAvailabilityGuard secretsAvailabilityGuard(
            InspectConfigurationUseCase inspectConfigurationUseCase,
            CmnServiceProperties properties) {
        return new SecretsAvailabilityGuard(
                inspectConfigurationUseCase, properties.startup().guardTimeout());
    }
}
