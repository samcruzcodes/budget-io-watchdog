package io.budget.watchdog.schema;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** Loads the JSON Schema files from src/main/resources/schemas with format checks ("uuid", "date-time") turned on. */
final class ContractSchemas {

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaRegistryConfig(
                    SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()));

    private ContractSchemas() {
    }

    static Schema load(String resource) {
        try (InputStream in = ContractSchemas.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("no such resource: " + resource);
            }
            return REGISTRY.getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
