package com.cmn.service.infrastructure.config;

import com.cmn.service.domain.port.in.InspectConfigurationUseCase;
import com.cmn.service.infrastructure.adapter.in.web.ConfigurationHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/** Wires the HTTP driving adapter. Functional routing, no annotated controllers. */
@Configuration(proxyBeanMethods = false)
public class WebRoutingConfiguration {

    @Bean
    ConfigurationHandler configurationHandler(InspectConfigurationUseCase inspectConfiguration) {
        return new ConfigurationHandler(inspectConfiguration);
    }

    @Bean
    RouterFunction<ServerResponse> configurationRoutes(ConfigurationHandler handler) {
        return RouterFunctions.route()
                .path("/api/v1", builder -> builder
                        .GET("/configuration", handler::snapshot)
                        .GET("/configuration/{key}", handler::entry)
                        .GET("/environment", handler::environment))
                .filter((request, next) -> next.handle(request))
                .build();
    }

    /** Root endpoint so a bare curl to the service says something useful. */
    @Bean
    RouterFunction<ServerResponse> indexRoute() {
        return RouterFunctions.route()
                .GET("/", request -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(java.util.Map.of(
                                "service", "cmn-service",
                                "endpoints", java.util.List.of(
                                        "/api/v1/environment",
                                        "/api/v1/configuration",
                                        "/api/v1/configuration/{key}",
                                        "/actuator/health"))))
                .build();
    }
}
