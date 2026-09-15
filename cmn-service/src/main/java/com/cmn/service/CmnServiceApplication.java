package com.cmn.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point.
 *
 * <p>Component scanning is restricted to the infrastructure package: the domain and
 * application layers are wired explicitly in
 * {@code infrastructure.config.HexagonalWiringConfiguration} and must stay free of
 * Spring annotations. Scanning them would invite annotations back in.
 */
@SpringBootApplication(scanBasePackages = "com.cmn.service.infrastructure")
public class CmnServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CmnServiceApplication.class, args);
    }
}
