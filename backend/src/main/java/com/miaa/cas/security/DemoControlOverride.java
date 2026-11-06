package com.miaa.cas.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * DEMONSTRATION ONLY: switches on {@link CurrentActor#overridesControls()} for the demo super administrator. Set by
 * application-dev.yml and the demo start script; empty by default, so a production server keeps every four-eyes control.
 */
@Component
public class DemoControlOverride {

    private static final Logger log = LoggerFactory.getLogger(DemoControlOverride.class);

    public DemoControlOverride(@Value("${cas.demo.superadmin-overrides-controls:false}") boolean enabled) {
        CurrentActor.enableDemoOverrides(enabled);
        if (enabled) {
            log.warn("DEMO: the {} role passes segregation-of-duties and approval-routing controls; demo data only.",
                    CurrentActor.DEMO_SUPERADMIN_ROLE);
        }
    }
}
