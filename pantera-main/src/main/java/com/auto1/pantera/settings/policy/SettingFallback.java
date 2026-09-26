/*
 * Copyright (c) 2025-2026 Auto1 Group
 * Maintainers: Auto1 DevOps Team
 * Lead Maintainer: Ayd Asraf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License v3.0.
 *
 * Originally based on Artipie (https://github.com/artipie/artipie), MIT License.
 */
package com.auto1.pantera.settings.policy;

import com.auto1.pantera.http.log.EcsLogger;
import java.util.function.Consumer;

/**
 * Per-key validation with fallback for a settings section.
 * <p>
 * An invalid value for ONE key must never discard the other keys of its
 * section: an operator typo in one field would otherwise silently reset
 * unrelated security settings (an fs-roots allowlist, a strict egress
 * mode) to their defaults. Each candidate is validated on its own and
 * only the offending key drops to its default, with a WARN naming it.
 * @since 2.2.9
 */
final class SettingFallback {

    /**
     * Logger name.
     */
    private static final String LOGGER = "com.auto1.pantera.settings.policy";

    /**
     * Section name used in the log event and message.
     */
    private final String section;

    /**
     * Ctor.
     * @param section Section name, e.g. {@code request_limits}
     */
    SettingFallback(final String section) {
        this.section = section;
    }

    /**
     * Returns the candidate if it passes validation, otherwise the fallback.
     * @param key Setting key, named in the warning
     * @param candidate Resolved candidate value
     * @param validate Validation that throws on an invalid candidate
     * @param fallback Default for this key alone
     * @param <T> Value type
     * @return Candidate or fallback
     */
    <T> T validOrDefault(
        final String key, final T candidate, final Consumer<T> validate, final T fallback
    ) {
        try {
            validate.accept(candidate);
            return candidate;
        } catch (final IllegalArgumentException | ArithmeticException bad) {
            EcsLogger.warn(LOGGER)
                .message(
                    String.format(
                        "Ignoring invalid %s setting '%s' (%s); using its default, other %s keys are kept",
                        this.section, key, bad.getMessage(), this.section
                    )
                )
                .eventCategory("configuration")
                .eventAction(this.section + "_settings_load")
                .eventOutcome("failure")
                .field("event.reason", key)
                .field("log.source", "application")
                .error(bad)
                .log();
            return fallback;
        }
    }
}
