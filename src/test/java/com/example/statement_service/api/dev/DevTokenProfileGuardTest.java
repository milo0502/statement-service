package com.example.statement_service.api.dev;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DevTokenProfileGuardTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DevTokenProfileGuard.class);

    @Test
    void startsWhenOnlyLocalDevelopmentProfileIsActive() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void startsWhenOnlyProductionLikeProfileIsActive() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("docker"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void failsStartupWhenLocalDevelopmentAndProductionLikeProfilesAreCombined() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("local", "docker"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                    assertThat(rootCause(context.getStartupFailure()))
                            .hasMessageContaining("development token endpoint");
                });
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
