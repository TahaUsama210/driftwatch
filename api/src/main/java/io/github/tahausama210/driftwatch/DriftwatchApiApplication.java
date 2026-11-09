package io.github.tahausama210.driftwatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Driftwatch API entry point.
 *
 * <p>The in-memory user store is excluded: callers authenticate with bearer tokens, never with a
 * username and password, and excluding it also stops Boot from logging a generated password.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class DriftwatchApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(DriftwatchApiApplication.class, args);
    }
}
