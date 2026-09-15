package com.cmn.service.infrastructure.adapter.in.web;

import com.cmn.service.domain.model.ConfigurationEntry;
import com.cmn.service.domain.model.ConfigurationSnapshot;
import com.cmn.service.domain.port.in.InspectConfigurationUseCase;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * Driving adapter: translates HTTP into use-case calls and domain objects into JSON.
 *
 * <p>Holds no business logic. Note that nothing here decides what to mask - the
 * domain already did, via {@link ConfigurationEntry#presentableValue()}. The web
 * layer cannot leak a secret by forgetting to.
 */
public class ConfigurationHandler {

    private final InspectConfigurationUseCase inspectConfiguration;

    public ConfigurationHandler(InspectConfigurationUseCase inspectConfiguration) {
        this.inspectConfiguration = inspectConfiguration;
    }

    /** {@code GET /api/v1/configuration} - the full snapshot. */
    public Mono<ServerResponse> snapshot(ServerRequest request) {
        return inspectConfiguration.currentSnapshot()
                .flatMap(snapshot -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(toPayload(snapshot)));
    }

    /** {@code GET /api/v1/configuration/{key}} - one entry, masked if sensitive. */
    public Mono<ServerResponse> entry(ServerRequest request) {
        var key = request.pathVariable("key");

        return inspectConfiguration.findEntry(key)
                .flatMap(entry -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(toPayload(entry)))
                .switchIfEmpty(ServerResponse.notFound().build());
    }

    /** {@code GET /api/v1/environment} - which environment this instance believes it is. */
    public Mono<ServerResponse> environment(ServerRequest request) {
        return inspectConfiguration.currentSnapshot()
                .flatMap(snapshot -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of(
                                "environment", snapshot.environment().profile(),
                                "secretsRequired", snapshot.environment().secretsRequired(),
                                "backedByOpenBao", snapshot.backedByOpenBao(),
                                "healthy", snapshot.satisfiesEnvironmentRequirements())));
    }

    // ------------------------------------------------------------------

    private Map<String, Object> toPayload(ConfigurationSnapshot snapshot) {
        return Map.of(
                "environment", snapshot.environment().profile(),
                "backedByOpenBao", snapshot.backedByOpenBao(),
                "satisfiesEnvironmentRequirements", snapshot.satisfiesEnvironmentRequirements(),
                "configCount", snapshot.configCount(),
                "secretCount", snapshot.secretCount(),
                "countByOrigin", snapshot.countByOrigin().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                e -> e.getKey().name(), Map.Entry::getValue)),
                "entries", toPayload(snapshot.entries()));
    }

    private List<Map<String, Object>> toPayload(List<ConfigurationEntry> entries) {
        return entries.stream().map(this::toPayload).toList();
    }

    private Map<String, Object> toPayload(ConfigurationEntry entry) {
        return Map.of(
                "key", entry.key().value(),
                "value", entry.presentableValue(),
                "sensitive", entry.sensitive(),
                "length", entry.length(),
                "origin", entry.origin().name());
    }
}
