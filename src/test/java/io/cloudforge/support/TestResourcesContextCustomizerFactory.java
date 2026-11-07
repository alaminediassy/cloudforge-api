package io.cloudforge.support;

import java.util.List;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

/** Applies the resource check to Spring Test contexts, including JPA test slices. */
public final class TestResourcesContextCustomizerFactory implements ContextCustomizerFactory {
    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass,
            List<ContextConfigurationAttributes> attributes) {
        return Guard.INSTANCE;
    }

    private enum Guard implements ContextCustomizer {
        INSTANCE;

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration config) {
            // Test properties are available here. Check before DataSource and Flyway beans start.
            context.addBeanFactoryPostProcessor(factory -> TestResourcesGuard.verify(context.getEnvironment()));
        }
    }
}
