package io.budget.watchdog;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * The watchdog service. Spring Boot's auto-configuration reads application.yml (and environment variables) and builds
 * the Kafka consumer, the database connection pool, and Flyway; our code is the listener and the SQL.
 */
@SpringBootApplication
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
