package com.cmn.service.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.cmn.service.domain.model.ConfigurationEntry;
import com.cmn.service.domain.model.ConfigurationKey;
import com.cmn.service.domain.model.ConfigurationOrigin;
import com.cmn.service.domain.model.Environment;
import com.cmn.service.domain.port.out.ConfigurationSourcePort;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * The payoff of the hexagon: the application layer is exercised with a hand-written
 * fake and plain JUnit. No Spring context, no OpenBao, no HTTP, no mocking framework.
 */
class ConfigurationInspectionServiceTest {

    @Test
    @DisplayName("snapshot combines the environment with everything the source yields")
    void buildsSnapshot() {
        var service = new ConfigurationInspectionService(new FakeSource(Environment.DEV, List.of(
                entry("POSTGRES_USER", "postgres", ConfigurationOrigin.OPENBAO),
                entry("POSTGRES_PASSWORD", "demo-pw", ConfigurationOrigin.OPENBAO),
                entry("cmn.inspection.x", "y", ConfigurationOrigin.LOCAL_FILE))));

        StepVerifier.create(service.currentSnapshot())
                .assertNext(snapshot -> {
                    assertThat(snapshot.environment()).isEqualTo(Environment.DEV);
                    assertThat(snapshot.entries()).hasSize(3);
                    assertThat(snapshot.secretCount()).isEqualTo(1);
                    assertThat(snapshot.configCount()).isEqualTo(2);
                    assertThat(snapshot.backedByOpenBao()).isTrue();
                    assertThat(snapshot.countByOrigin())
                            .containsEntry(ConfigurationOrigin.OPENBAO, 2L)
                            .containsEntry(ConfigurationOrigin.LOCAL_FILE, 1L);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("staging without any OpenBao value does not satisfy its requirements")
    void stagingRequiresOpenBao() {
        var service = new ConfigurationInspectionService(new FakeSource(Environment.STAGING,
                List.of(entry("SOME_KEY", "from-yaml", ConfigurationOrigin.LOCAL_FILE))));

        StepVerifier.create(service.currentSnapshot())
                .assertNext(snapshot -> {
                    assertThat(snapshot.backedByOpenBao()).isFalse();
                    assertThat(snapshot.satisfiesEnvironmentRequirements()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("local without OpenBao is still considered satisfied")
    void localToleratesMissingOpenBao() {
        var service = new ConfigurationInspectionService(new FakeSource(Environment.LOCAL,
                List.of(entry("SOME_KEY", "from-yaml", ConfigurationOrigin.LOCAL_FILE))));

        StepVerifier.create(service.currentSnapshot())
                .assertNext(snapshot ->
                        assertThat(snapshot.satisfiesEnvironmentRequirements()).isTrue())
                .verifyComplete();
    }

    @Test
    @DisplayName("an unknown key yields an empty result, not an error")
    void missingKeyIsEmpty() {
        var service = new ConfigurationInspectionService(new FakeSource(Environment.DEV, List.of()));

        StepVerifier.create(service.findEntry("NOT_THERE")).verifyComplete();
    }

    @Test
    @DisplayName("a blank key is rejected")
    void blankKeyIsRejected() {
        var service = new ConfigurationInspectionService(new FakeSource(Environment.DEV, List.of()));

        StepVerifier.create(service.findEntry("  "))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    // ------------------------------------------------------------------

    private static ConfigurationEntry entry(String key, String value, ConfigurationOrigin origin) {
        return new ConfigurationEntry(new ConfigurationKey(key), value, origin);
    }

    /** A driven port implemented in four lines, because the port is four methods wide. */
    private record FakeSource(Environment environment, List<ConfigurationEntry> entries)
            implements ConfigurationSourcePort {

        @Override
        public Flux<ConfigurationEntry> loadAll() {
            return Flux.fromIterable(entries);
        }

        @Override
        public Mono<ConfigurationEntry> findByKey(String key) {
            return Mono.justOrEmpty(
                    entries.stream().filter(e -> e.key().value().equals(key)).findFirst());
        }

        @Override
        public Mono<Environment> currentEnvironment() {
            return Mono.just(environment);
        }
    }
}
