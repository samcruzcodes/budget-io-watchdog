package io.budget.watchdog;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/**
 * The watchdog service. Spring Boot's auto-configuration reads application.yml (and environment variables) and builds
 * the Kafka consumer, the database connection pool, and Flyway. Our code is the listener, the SQL, and the detection
 * rules; {@code @ConfigurationPropertiesScan} finds each rule's Settings record and fills it from application.yml.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class WatchdogApplication {

    public static void main(String[] args) {
        SpringApplication.run(WatchdogApplication.class, args);
    }

    /** The real clock, as a bean so tests can replace it. Used for processing time, never for rule logic. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
