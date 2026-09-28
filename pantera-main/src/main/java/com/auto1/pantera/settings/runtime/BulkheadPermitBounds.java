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
package com.auto1.pantera.settings.runtime;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonValue;

/**
 * Cross-field rule of the bulkhead permit settings:
 * {@code min_permits <= initial_permits <= max_permits}. Each key is range
 * checked on its own; this checks the combination a change of one of them
 * (or its reset to the default) would leave in effect.
 *
 * @since 2.2.9
 */
public final class BulkheadPermitBounds {

    /**
     * Stored rows ({@code {"value": <typed>}}) by key; absent keys use the
     * catalog default. Read only for a change of a permit key.
     */
    private final Supplier<Map<String, JsonObject>> rows;

    /**
     * Ctor.
     * @param rows Source of the stored settings rows by key
     */
    public BulkheadPermitBounds(final Supplier<Map<String, JsonObject>> rows) {
        this.rows = rows;
    }

    /**
     * Whether a key takes part in the rule.
     * @param key Settings key
     * @return True for the min, max and initial permit keys
     */
    public boolean covers(final String key) {
        return SettingsKey.BULKHEAD_MIN_PERMITS.key().equals(key)
            || SettingsKey.BULKHEAD_MAX_PERMITS.key().equals(key)
            || SettingsKey.BULKHEAD_INITIAL_PERMITS.key().equals(key);
    }

    /**
     * The violation the change would cause.
     * @param key Changed key
     * @param value New value, empty for a reset to the default
     * @return Error message, empty when the combination stays valid
     */
    public Optional<String> violationAfter(final String key, final Optional<Number> value) {
        if (!this.covers(key)) {
            return Optional.empty();
        }
        final Map<String, JsonObject> stored = this.rows.get();
        final long min = BulkheadPermitBounds.effective(
            stored, SettingsKey.BULKHEAD_MIN_PERMITS, key, value
        );
        final long max = BulkheadPermitBounds.effective(
            stored, SettingsKey.BULKHEAD_MAX_PERMITS, key, value
        );
        final long initial = BulkheadPermitBounds.effective(
            stored, SettingsKey.BULKHEAD_INITIAL_PERMITS, key, value
        );
        final Optional<String> result;
        if (min > max) {
            result = Optional.of(
                String.format(
                    "min_permits (%d) must not exceed max_permits (%d)", min, max
                )
            );
        } else if (initial < min || initial > max) {
            result = Optional.of(
                String.format(
                    "initial_permits (%d) must lie between min_permits (%d) and max_permits (%d)",
                    initial, min, max
                )
            );
        } else {
            result = Optional.empty();
        }
        return result;
    }

    /**
     * Value of a key once the change is applied.
     * @param stored Stored rows by key
     * @param setting Key to read
     * @param changed Changed key
     * @param value New value of the changed key, empty for its default
     * @return Effective value
     */
    private static long effective(
        final Map<String, JsonObject> stored, final SettingsKey setting,
        final String changed, final Optional<Number> value
    ) {
        final long result;
        if (setting.key().equals(changed)) {
            result = value.map(Number::longValue)
                .orElseGet(() -> Long.parseLong(setting.defaultRepr()));
        } else {
            final JsonObject row = stored.get(setting.key());
            final JsonValue current = row == null ? null : row.get("value");
            if (current instanceof JsonNumber number) {
                result = number.longValue();
            } else {
                result = Long.parseLong(setting.defaultRepr());
            }
        }
        return result;
    }
}
