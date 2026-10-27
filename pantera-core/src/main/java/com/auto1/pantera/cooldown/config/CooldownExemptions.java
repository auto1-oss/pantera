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
package com.auto1.pantera.cooldown.config;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Packages of one repository that cooldown never blocks.
 *
 * <p>Compiled once from its {@link Rule}s, immutable, and matched on every
 * cooldown decision, so a match costs a hash lookup (exact names) or one
 * {@code startsWith} per prefix rule, with no allocation unless the name has
 * upper-case characters. The clock is read only when a rule matched, to
 * honour its expiry.</p>
 *
 * <p>Names match case-insensitively, as written in the cooldown blocked list:
 * {@code groupId.artifactId} for Maven and Gradle, {@code @scope/name} for
 * npm, the image path for Docker, the module path for Go, {@code
 * vendor/package} for Composer, the normalised project name for PyPI.</p>
 *
 * @since 2.2.11
 */
public final class CooldownExemptions {

    /**
     * No exemptions.
     */
    public static final CooldownExemptions NONE = new CooldownExemptions(List.of());

    /**
     * Maximum pattern length.
     */
    private static final int MAX_PATTERN = 512;

    /**
     * Expiry used for rules that never expire.
     */
    private static final long NEVER = Long.MAX_VALUE;

    /**
     * Rules as given.
     */
    private final List<Rule> rules;

    /**
     * Expiry (epoch millis) by exact lower-case name.
     */
    private final Map<String, Long> exact;

    /**
     * Lower-case prefixes of the {@code prefix*} rules.
     */
    private final String[] prefixes;

    /**
     * Expiry (epoch millis) of each prefix, same index.
     */
    private final long[] prefixExpiry;

    /**
     * Ctor.
     *
     * @param rules Rules
     */
    public CooldownExemptions(final List<Rule> rules) {
        this.rules = List.copyOf(rules);
        this.exact = new HashMap<>();
        final List<String> pre = new ArrayList<>();
        final List<Long> preExp = new ArrayList<>();
        for (final Rule rule : this.rules) {
            final long expiry = rule.expiresAt().map(Instant::toEpochMilli).orElse(NEVER);
            final String key = rule.pattern().toLowerCase(Locale.ROOT);
            if (key.endsWith("*")) {
                pre.add(key.substring(0, key.length() - 1));
                preExp.add(expiry);
            } else {
                this.exact.merge(key, expiry, Math::max);
            }
        }
        this.prefixes = pre.toArray(new String[0]);
        this.prefixExpiry = preExp.stream().mapToLong(Long::longValue).toArray();
    }

    /**
     * Whether a package is exempt now.
     *
     * @param name Package name as the adapter reports it
     * @return True when an unexpired rule matches
     */
    public boolean exempts(final String name) {
        if (name == null || this.rules.isEmpty()) {
            return false;
        }
        final String key = CooldownExemptions.lower(name);
        long expiry = this.exact.getOrDefault(key, 0L);
        for (int idx = 0; idx < this.prefixes.length; ++idx) {
            if (this.prefixExpiry[idx] > expiry && key.startsWith(this.prefixes[idx])) {
                expiry = this.prefixExpiry[idx];
            }
        }
        return expiry == NEVER || expiry > 0L && expiry > System.currentTimeMillis();
    }

    /**
     * Whether there are no rules.
     *
     * @return True when empty
     */
    public boolean isEmpty() {
        return this.rules.isEmpty();
    }

    /**
     * Rules as given.
     *
     * @return Rules
     */
    public List<Rule> rules() {
        return this.rules;
    }

    /**
     * Lower-case a name, returning the same instance when it already is.
     */
    private static String lower(final String name) {
        for (int idx = 0; idx < name.length(); ++idx) {
            if (Character.isUpperCase(name.charAt(idx))) {
                return name.toLowerCase(Locale.ROOT);
            }
        }
        return name;
    }

    /**
     * One exemption.
     *
     * @param pattern Exact package name, or a prefix ending in a single {@code *}
     * @param expiresAt When the exemption stops applying; empty for never
     * @param reason Free-text reason, empty when none was given
     * @param createdBy User who added it
     * @param createdAt When it was added
     */
    public record Rule(
        String pattern,
        Optional<Instant> expiresAt,
        String reason,
        String createdBy,
        Instant createdAt
    ) {
        /**
         * Validating ctor.
         *
         * @throws IllegalArgumentException When the pattern is not valid
         */
        public Rule {
            Objects.requireNonNull(pattern, "pattern");
            Objects.requireNonNull(expiresAt, "expiresAt");
            Objects.requireNonNull(createdBy, "createdBy");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(reason, "reason");
            if (pattern.isEmpty() || pattern.length() > MAX_PATTERN) {
                throw new IllegalArgumentException(
                    "pattern must be 1-" + MAX_PATTERN + " characters"
                );
            }
            for (int idx = 0; idx < pattern.length(); ++idx) {
                final char chr = pattern.charAt(idx);
                if (Character.isWhitespace(chr) || Character.isISOControl(chr)) {
                    throw new IllegalArgumentException("pattern must not contain whitespace: " + pattern);
                }
                if (chr == '*' && idx != pattern.length() - 1) {
                    throw new IllegalArgumentException(
                        "'*' is only allowed as the last character: " + pattern
                    );
                }
            }
            if ("*".equals(pattern)) {
                throw new IllegalArgumentException(
                    "'*' alone would exempt every package; disable cooldown for the repository instead"
                );
            }
        }
    }
}
