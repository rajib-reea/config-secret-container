package com.cmn.service.infrastructure.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Service-specific settings, bound from {@code cmn.*}.
 *
 * @param inspection controls what the configuration endpoints report
 */
@ConfigurationProperties(prefix = "cmn")
public record CmnServiceProperties(Inspection inspection, Startup startup) {

    public CmnServiceProperties {
        if (inspection == null) {
            inspection = new Inspection(List.of());
        }
        if (startup == null) {
            startup = new Startup(null);
        }
    }

    /**
     * @param guardTimeout how long the startup guard waits for configuration to
     *                     resolve before failing. Defaults to 10s.
     */
    public record Startup(java.time.Duration guardTimeout) {

        public Startup {
            guardTimeout = guardTimeout == null ? java.time.Duration.ofSeconds(10) : guardTimeout;
        }
    }

    /**
     * @param includePrefixes property-name prefixes to report even when the value did
     *                        not come from OpenBao. Values from OpenBao are always
     *                        reported; this keeps everything else out of the response
     *                        rather than dumping the JVM's entire property space.
     */
    public record Inspection(List<String> includePrefixes) {

        public Inspection {
            includePrefixes = includePrefixes == null ? List.of() : List.copyOf(includePrefixes);
        }
    }
}
