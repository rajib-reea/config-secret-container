package com.cmn.service.domain.port.in;

import com.cmn.service.domain.model.ConfigurationEntry;
import com.cmn.service.domain.model.ConfigurationSnapshot;
import reactor.core.publisher.Mono;

/**
 * Driving port: what the outside world may ask this service to do.
 *
 * <p>Implemented by the application layer, called by driving adapters
 * (the WebFlux handler).
 *
 * <p>Reactor types appear in the signature because the whole stack is reactive.
 * {@code Mono}/{@code Flux} are treated as part of the language here rather than
 * as a framework detail - the alternative, blocking in the adapter to keep the
 * port synchronous, would defeat the purpose of WebFlux.
 */
public interface InspectConfigurationUseCase {

    /** Everything this instance resolved, with secrets masked by the domain. */
    Mono<ConfigurationSnapshot> currentSnapshot();

    /**
     * A single entry by key.
     *
     * @return empty when no such key was resolved
     */
    Mono<ConfigurationEntry> findEntry(String key);
}
