/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.timeout;

import java.time.Duration;

/**
 * Configuration for auto-block behavior. All values configurable via YAML.
 *
 * @since 1.20.13
 */
public record AutoBlockSettings(
    int failureThreshold,
    Duration initialBlockDuration,
    Duration maxBlockDuration
) {

    public static AutoBlockSettings defaults() {
        return new AutoBlockSettings(3, Duration.ofSeconds(40), Duration.ofMinutes(5));
    }
}
