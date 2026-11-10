package com.tripplanner.config;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.junit.jupiter.api.Assertions.*;
class AppPropertiesBindingTests {
    @Configuration(proxyBeanMethods=false)
    @EnableConfigurationProperties(AppProperties.class)
    static class Settings {}
    @Test void deployedPropertiesBindOpenAiIncludingReasoningEffort() {
        new ApplicationContextRunner().withUserConfiguration(Settings.class).withPropertyValues(
            "app.openai.api-key=test-key","app.openai.model=gpt-6-luna",
            "app.openai.base-url=https://example.invalid/v1","app.openai.reasoning-effort=low")
            .run(context -> {
                assertNull(context.getStartupFailure());
                var props=context.getBean(AppProperties.class);
                assertNotNull(props.openai(),"OpenAI settings must bind at server startup");
                assertTrue(props.openai().enabled()); assertEquals("gpt-6-luna",props.openai().model());
                assertEquals("low",props.openai().reasoningEffort());
            });
    }
    @Test void existingEnvironmentWithoutNewEffortSettingStillLoadsKeyAndModel() {
        new ApplicationContextRunner().withUserConfiguration(Settings.class).withPropertyValues(
            "app.openai.api-key=test-key","app.openai.model=gpt-6-luna",
            "app.openai.base-url=https://example.invalid/v1")
            .run(context -> {
                assertNull(context.getStartupFailure());var props=context.getBean(AppProperties.class);
                assertNotNull(props.openai());assertTrue(props.openai().enabled());
                assertEquals("gpt-6-luna",props.openai().model());assertEquals("low",props.openai().reasoningEffort());
            });
    }
    @Test void emptyApiKeyLoadsSettingsAndSelectsSampleModeWithoutNullPointer() {
        new ApplicationContextRunner().withUserConfiguration(Settings.class).withPropertyValues(
            "app.openai.api-key=","app.openai.model=gpt-6-luna",
            "app.openai.base-url=https://example.invalid/v1","app.openai.reasoning-effort=low")
            .run(context -> {
                assertNull(context.getStartupFailure());var props=context.getBean(AppProperties.class);
                assertNotNull(props.openai());assertFalse(props.openai().enabled());
            });
    }

}
