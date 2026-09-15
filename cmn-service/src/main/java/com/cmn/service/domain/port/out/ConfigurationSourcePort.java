package com.cmn.service.domain.port.out;

import com.cmn.service.domain.model.ConfigurationEntry;
import com.cmn.service.domain.model.Environment;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Driven port: where resolved configuration comes from.
 *
 * <p>The application layer depends on this interface; the infrastructure layer
 * supplies the implementation. Swapping OpenBao for a file, a database or a test
 * double is a matter of providing a different adapter - nothing in the domain or
 * application layer changes.
 */
public interface ConfigurationSourcePort {

    /** Every configuration value visible to this instance. */
    Flux<ConfigurationEntry> loadAll();

    /**
     * One value by key.
     *
     * @return empty when the key is not present
     */
    Mono<ConfigurationEntry> findByKey(String key);

    /** The environment this instance is running as. */
    Mono<Environment> currentEnvironment();
}
