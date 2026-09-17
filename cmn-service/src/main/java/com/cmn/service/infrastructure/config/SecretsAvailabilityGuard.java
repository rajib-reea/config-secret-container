package com.cmn.service.infrastructure.config;

import com.cmn.service.domain.port.in.InspectConfigurationUseCase;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

/**
 * Refuses to finish starting when an environment that requires secrets did not get any.
 *
 * <p>This exists because Spring Cloud Vault does not fail on a <em>missing</em> KV
 * document. A non-{@code optional:} import such as
 * {@code spring.config.import: vault://cmn/config/staging} logs
 *
 * <pre>Vault location [cmn/config/staging] not resolvable: Not found</pre>
 *
 * and carries on. {@code spring.cloud.vault.fail-fast} covers an unreachable or
 * sealed server, not an empty path. Without this guard a staging or production
 * instance would start happily on bundled defaults and serve traffic with no real
 * configuration - the exact failure the profiles were meant to prevent.
 *
 * <p>Runs as an {@link InitializingBean} so the failure happens during context
 * refresh, before the HTTP listener accepts anything.
 */
public class SecretsAvailabilityGuard implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SecretsAvailabilityGuard.class);

    private final InspectConfigurationUseCase inspectConfiguration;
    private final Duration timeout;

    public SecretsAvailabilityGuard(
            InspectConfigurationUseCase inspectConfiguration, Duration timeout) {
        this.inspectConfiguration = inspectConfiguration;
        this.timeout = timeout;
    }

    @Override
    public void afterPropertiesSet() {
        // Blocking is correct here: this is startup, on the main thread, before any
        // request can arrive. Nothing is waiting on this event loop yet.
        var snapshot = inspectConfiguration.currentSnapshot().block(timeout);

        if (snapshot == null) {
            throw new IllegalStateException("Could not resolve configuration at startup");
        }

        var environment = snapshot.environment();

        if (snapshot.satisfiesEnvironmentRequirements()) {
            log.info("Configuration for '{}' resolved: {} values ({} from Vault, {} from files), "
                            + "{} secrets",
                    environment.profile(),
                    snapshot.entries().size(),
                    snapshot.countByOrigin().getOrDefault(
                            com.cmn.service.domain.model.ConfigurationOrigin.VAULT, 0L),
                    snapshot.countByOrigin().getOrDefault(
                            com.cmn.service.domain.model.ConfigurationOrigin.LOCAL_FILE, 0L),
                    snapshot.secretCount());
            return;
        }

        throw new IllegalStateException(("""
                Refusing to start in '%s': no configuration came from Vault.

                This environment requires secrets. Every value currently resolved came \
                from bundled defaults, which means the store returned nothing for the \
                configured paths.

                Likely causes:
                  * cmn/config/%s and cmn/secret/%s do not exist in Vault yet
                  * the KV mount name does not match spring.cloud.vault.kv.backend
                  * the token or AppRole cannot read those paths

                Note that a missing KV path is NOT an error to Spring Cloud Vault - it \
                logs "not resolvable: Not found" and continues - which is why this check \
                exists.

                Populate the paths, for example:
                  cd openbao && PROJECT_NAME=cmn ./env-to-bao.sh   (writes config/<profile>)\
                """).formatted(environment.profile(), environment.profile(), environment.profile()));
    }
}
