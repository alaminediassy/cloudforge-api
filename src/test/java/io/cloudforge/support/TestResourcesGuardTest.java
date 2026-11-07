package io.cloudforge.support;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import io.cloudforge.CloudforgeApiApplication;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestResourcesGuardTest {
    private static final AtomicInteger connections = new AtomicInteger();
    private static final AtomicInteger migrations = new AtomicInteger();
    private static final AtomicInteger redisConnections = new AtomicInteger();

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    context.getEnvironment().setActiveProfiles("test");
                    new TestResourcesContextCustomizerFactory().createContextCustomizer(getClass(), List.of())
                            .customizeContext(context, null);
                })
                .withPropertyValues("spring.datasource.url=jdbc:postgresql://localhost:5433/cloudforge_test",
                        "spring.datasource.username=cloudforge_test", "spring.datasource.password=local-test-only",
                        "spring.data.redis.host=localhost", "spring.data.redis.port=6390",
                        "spring.data.redis.database=1", "spring.flyway.enabled=true");
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "127.0.0.1"})
    void acceptsDedicatedLocalAndCiResources(String host) {
        runner().withPropertyValues("spring.datasource.url=jdbc:postgresql://" + host + ":5433/cloudforge_test",
                "spring.data.redis.host=" + host).run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "spring.datasource.url=jdbc:postgresql://localhost:5432/cloudforge_test",
            "spring.datasource.url=jdbc:postgresql://localhost:5433/cloudforge",
            "spring.datasource.url=jdbc:postgresql://remote.example:5433/cloudforge_test",
            "spring.datasource.url=jdbc:postgresql://localhost:5433/cloudforge_test?service=other",
            "spring.datasource.url=",
            "spring.data.redis.host=remote.example",
            "spring.data.redis.port=6380",
            "spring.data.redis.database=0",
            "spring.data.redis.url=redis://localhost:6380",
            "spring.data.redis.sentinel.master=other",
            "spring.data.redis.cluster.nodes=localhost:6380",
            "spring.datasource.hikari.jdbc-url=jdbc:postgresql://localhost:5432/cloudforge",
            "spring.datasource.hikari.data-source-properties.serverName=remote.example",
            "spring.datasource.jndi-name=other",
            "spring.flyway.url=jdbc:postgresql://localhost:5432/cloudforge",
            "spring.flyway.user=other"
    })
    void rejectsWrongDestinationsAndAlternativePropertiesBeforePersistence(String property) {
        connections.set(0);
        migrations.set(0);
        redisConnections.set(0);
        runner().withUserConfiguration(PersistenceProbe.class).withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("Test resources rejected");
            assertThat(context.getStartupFailure().getMessage()).doesNotContain("jdbc:", "redis:", "remote.example");
            assertThat(connections.get()).isZero();
            assertThat(migrations.get()).isZero();
            assertThat(redisConnections.get()).isZero();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod", "test,dev", "test,prod", ""})
    void rejectsMissingOrMixedTestProfile(String profiles) {
        runner().withPropertyValues("spring.profiles.active=" + profiles)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(
                profiles.isEmpty() ? new String[0] : profiles.split(",")))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void checksEnvironmentVariableOverrides() {
        runner().withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.SystemEnvironmentPropertySource("test-environment",
                        java.util.Map.of("SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/cloudforge"))))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void automaticallyChecksSpringTestPropertiesBeforePersistence() {
        assertRejected(InvalidFixture.class);
    }

    @Test
    void checksStableDynamicPropertyOverridesBeforePersistence() {
        assertRejected(DynamicFixture.class);
    }

    @Test
    void automaticallyChecksJpaSlicesBeforeFlyway() {
        assertRejected(InvalidJpaFixture.class);
    }

    private void assertRejected(Class<?> fixture) {
        connections.set(0);
        migrations.set(0);
        redisConnections.set(0);
        var manager = new TestContextManager(fixture);
        Throwable failure = assertThrows(IllegalStateException.class,
                () -> manager.getTestContext().getApplicationContext());
        while (failure.getCause() != null) failure = failure.getCause();
        assertThat(failure).hasMessageContaining("Test resources rejected");
        assertThat(connections.get()).isZero();
        assertThat(migrations.get()).isZero();
        assertThat(redisConnections.get()).isZero();
    }

    @Configuration(proxyBeanMethods = false)
    static class PersistenceProbe {
        // These factories fail if the guard lets persistence initialization start.
        @Bean DataSource dataSource() {
            connections.incrementAndGet();
            throw new AssertionError("Connection factory reached");
        }
        @Bean RedisConnectionFactory redisConnectionFactory() {
            redisConnections.incrementAndGet();
            throw new AssertionError("Redis factory reached");
        }
        @Bean Flyway flyway() {
            migrations.incrementAndGet();
            throw new AssertionError("Migration factory reached");
        }
    }

    @ContextConfiguration(classes = PersistenceProbe.class)
    @ActiveProfiles("test")
    @TestPropertySource(properties = {
            "spring.datasource.url=jdbc:postgresql://localhost:5433/cloudforge_test",
            "spring.data.redis.host=localhost", "spring.data.redis.port=6380", "spring.data.redis.database=1"
    })
    static class InvalidFixture {}

    @DataJpaTest
    @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
    @ContextConfiguration(classes = CloudforgeApiApplication.class)
    @ActiveProfiles("test")
    @TestPropertySource(properties = "spring.datasource.url=jdbc:postgresql://localhost:5432/cloudforge")
    static class InvalidJpaFixture {}

    @ContextConfiguration(classes = PersistenceProbe.class)
    @ActiveProfiles("test")
    @TestPropertySource(properties = {
            "spring.datasource.url=jdbc:postgresql://localhost:5433/cloudforge_test",
            "spring.data.redis.host=localhost", "spring.data.redis.port=6390", "spring.data.redis.database=1"
    })
    static class DynamicFixture {
        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            registry.add("spring.data.redis.port", () -> "6380");
        }
    }
}
