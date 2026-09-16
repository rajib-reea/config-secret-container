package com.cmn.service.infrastructure.adapter.out.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.cmn.service.domain.model.ConfigurationOrigin;
import com.cmn.service.domain.model.Environment;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import reactor.test.StepVerifier;

/**
 * Covers the seam the pure-domain tests cannot reach: attributing a Spring property
 * source to an origin.
 *
 * <p>The property-source names here are the real ones Spring Cloud Vault produces.
 * It names each source after the KV path it read - {@code cmn/config/dev} - with no
 * mention of "vault" anywhere. An earlier version of this adapter looked for the
 * substring "vault" and silently attributed every OpenBao value to
 * {@code LOCAL_FILE}, which made the service report {@code backedByOpenBao: false}
 * while running entirely on OpenBao data. These tests exist to keep that fixed.
 */
class SpringEnvironmentConfigurationAdapterTest {

    private static final List<String> OPENBAO_PREFIXES = List.of("cmn/");
    private static final List<String> INCLUDE_PREFIXES = List.of("cmn.", "spring.application.");

    @Test
    @DisplayName("values from a vault-named property source are attributed to OpenBao")
    void attributesVaultSourcesToOpenBao() {
        var environment = new StandardEnvironment();
        environment.setActiveProfiles("dev");
        environment.getPropertySources().addFirst(new MapPropertySource(
                "cmn/secret/dev", Map.of("POSTGRES_PASSWORD", "demo-pw")));
        environment.getPropertySources().addFirst(new MapPropertySource(
                "cmn/config/dev", Map.of("POSTGRES_USER", "postgres")));

        var adapter = adapter(environment);

        StepVerifier.create(adapter.loadAll().collectList())
                .assertNext(entries -> {
                    assertThat(entries).hasSize(2);
                    assertThat(entries)
                            .allSatisfy(e -> assertThat(e.origin())
                                    .isEqualTo(ConfigurationOrigin.OPENBAO));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("a bundled yml is attributed to LOCAL_FILE and filtered by prefix")
    void attributesAndFiltersLocalFiles() {
        var environment = new StandardEnvironment();
        environment.setActiveProfiles("local");
        environment.getPropertySources().addFirst(new MapPropertySource(
                "Config resource 'class path resource [application-local.yml]'",
                Map.of(
                        "cmn.inspection.include-prefixes[0]", "cmn.",
                        "server.port", "8080")));

        var adapter = adapter(environment);

        StepVerifier.create(adapter.loadAll().collectList())
                .assertNext(entries -> {
                    // server.port does not match an include prefix, so it is excluded.
                    assertThat(entries).hasSize(1);
                    assertThat(entries.getFirst().key().value())
                            .isEqualTo("cmn.inspection.include-prefixes[0]");
                    assertThat(entries.getFirst().origin())
                            .isEqualTo(ConfigurationOrigin.LOCAL_FILE);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("everything from OpenBao is reported regardless of prefix")
    void openBaoValuesIgnoreIncludePrefixes() {
        var environment = new StandardEnvironment();
        environment.setActiveProfiles("dev");
        environment.getPropertySources().addFirst(new MapPropertySource(
                "cmn/config/dev",
                Map.of("TOTALLY_UNPREFIXED", "value", "ANOTHER_ONE", "value")));

        var adapter = adapter(environment);

        StepVerifier.create(adapter.loadAll().collectList())
                .assertNext(entries -> assertThat(entries).hasSize(2))
                .verifyComplete();
    }

    @Test
    @DisplayName("higher-precedence sources win, and the duplicate is not reported twice")
    void respectsPropertySourcePrecedence() {
        var environment = new StandardEnvironment();
        environment.setActiveProfiles("dev");
        environment.getPropertySources().addLast(new MapPropertySource(
                "cmn/config/dev", Map.of("SHARED_KEY", "from-openbao")));
        environment.getPropertySources().addFirst(new MapPropertySource(
                "commandLineArgs", Map.of("SHARED_KEY", "from-cli")));

        var adapter = adapter(environment);

        StepVerifier.create(adapter.loadAll().collectList())
                .assertNext(entries -> {
                    assertThat(entries).hasSize(1);
                    assertThat(entries.getFirst().rawValue()).isEqualTo("from-cli");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("a secret read through the adapter is still masked by the domain")
    void masksSecretsEndToEnd() {
        var environment = new StandardEnvironment();
        environment.setActiveProfiles("dev");
        environment.getPropertySources().addFirst(new MapPropertySource(
                "cmn/secret/dev", Map.of("WORKFLOW_JWT_SECRET", "demo-workflow-jwt-secret")));

        var adapter = adapter(environment);

        StepVerifier.create(adapter.findByKey("WORKFLOW_JWT_SECRET"))
                .assertNext(entry -> {
                    assertThat(entry.sensitive()).isTrue();
                    assertThat(entry.presentableValue()).isEqualTo("********");
                    assertThat(entry.rawValue()).isEqualTo("demo-workflow-jwt-secret");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("the active profile determines the environment")
    void resolvesEnvironmentFromActiveProfile() {
        var environment = new StandardEnvironment();
        environment.setActiveProfiles("staging");

        StepVerifier.create(adapter(environment).currentEnvironment())
                .expectNext(Environment.STAGING)
                .verifyComplete();
    }

    @Test
    @DisplayName("an unknown key yields empty")
    void unknownKeyIsEmpty() {
        var environment = new StandardEnvironment();
        environment.setActiveProfiles("dev");

        StepVerifier.create(adapter(environment).findByKey("NOT_PRESENT")).verifyComplete();
    }

    private static SpringEnvironmentConfigurationAdapter adapter(StandardEnvironment environment) {
        return new SpringEnvironmentConfigurationAdapter(
                environment, INCLUDE_PREFIXES, OPENBAO_PREFIXES);
    }
}
