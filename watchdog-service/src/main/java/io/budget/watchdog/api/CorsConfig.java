package io.budget.watchdog.api;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets the browser pages listed in {@code watchdog.api.cors-allowed-origins} (Budget.io's) read the spending summary.
 * Only that endpoint: the dashboard will be served from the same origin as the API, so it needs no CORS.
 */
@Configuration
class CorsConfig implements WebMvcConfigurer {

    @ConfigurationProperties("watchdog.api")
    record Settings(List<String> corsAllowedOrigins) {

        Settings {
            corsAllowedOrigins = corsAllowedOrigins == null ? List.of() : List.copyOf(corsAllowedOrigins);
        }
    }

    private final Settings settings;

    CorsConfig(Settings settings) {
        this.settings = settings;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/spending/**")
                .allowedOrigins(settings.corsAllowedOrigins().toArray(String[]::new))
                .allowedMethods("GET");
    }
}
