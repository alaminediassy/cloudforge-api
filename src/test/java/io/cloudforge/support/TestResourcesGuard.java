package io.cloudforge.support;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;

/** Checks the fixed test connections before normal Spring beans are created. */
final class TestResourcesGuard {
    private TestResourcesGuard() {}

    static void verify(ConfigurableEnvironment environment) {
        try {
            if (!Set.copyOf(Arrays.asList(environment.getActiveProfiles())).equals(Set.of("test"))) {
                throw new IllegalStateException();
            }
            Binder binder = Binder.get(environment);
            Map<String, String> database = properties(binder, "spring.datasource");
            Map<String, String> redis = properties(binder, "spring.data.redis");
            Map<String, String> flyway = properties(binder, "spring.flyway");
            allowOnly(database, Set.of("url", "username", "password"));
            allowOnly(redis, Set.of("host", "port", "database"));
            allowOnly(flyway, Set.of("enabled", "locations"));
            if (!Set.of("jdbc:postgresql://localhost:5433/cloudforge_test",
                    "jdbc:postgresql://127.0.0.1:5433/cloudforge_test").contains(database.getOrDefault("url", ""))
                    || !Set.of("localhost", "127.0.0.1").contains(redis.getOrDefault("host", ""))
                    || !"6390".equals(redis.get("port")) || !"1".equals(redis.get("database"))) {
                throw new IllegalStateException();
            }
        } catch (RuntimeException failure) {
            // Binding errors can contain credentials. Do not keep their message or cause.
            throw new IllegalStateException("Test resources rejected: use only the dedicated test profile and connections.");
        }
    }

    private static Map<String, String> properties(Binder binder, String prefix) {
        return binder.bind(prefix, Bindable.mapOf(String.class, String.class)).orElse(Map.of());
    }

    private static void allowOnly(Map<String, String> values, Set<String> allowed) {
        if (!allowed.containsAll(values.keySet())) throw new IllegalStateException();
    }
}
