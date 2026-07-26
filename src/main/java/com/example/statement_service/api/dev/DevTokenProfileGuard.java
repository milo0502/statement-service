package com.example.statement_service.api.dev;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Fails startup when the development token endpoint is enabled with a
 * production-like profile.
 */
@Component
public class DevTokenProfileGuard {

    public DevTokenProfileGuard(Environment environment) {
        boolean localDevelopment = environment.acceptsProfiles(Profiles.of("local", "dev"));
        boolean productionLike = environment.acceptsProfiles(Profiles.of(
                "docker", "stage", "staging", "prod", "production"
        ));

        if (localDevelopment && productionLike) {
            throw new IllegalStateException(
                    "The development token endpoint can only be enabled with local/dev profiles. " +
                            "Do not combine local/dev with docker, stage, staging, prod, or production profiles."
            );
        }
    }
}
