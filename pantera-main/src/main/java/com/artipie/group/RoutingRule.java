/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.group;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Routing rule for group repositories.
 * Routes specific request paths to designated members,
 * preventing unnecessary upstream queries to non-matching members.
 *
 * @since 1.20.13
 */
public sealed interface RoutingRule permits RoutingRule.PathPrefix, RoutingRule.PathPattern {

    /**
     * The member this rule applies to.
     * @return Member repository name
     */
    String member();

    /**
     * Check if a request path matches this rule.
     * @param path Request path (e.g., "/com/example/foo/1.0/foo-1.0.jar")
     * @return True if this rule matches the path
     */
    boolean matches(String path);

    /**
     * Prefix-based routing rule.
     * Matches any path that starts with the specified prefix.
     *
     * @param member Member repository name
     * @param prefix Path prefix to match (e.g., "com/mycompany/")
     */
    record PathPrefix(String member, String prefix) implements RoutingRule {

        /**
         * Ctor.
         * @param member Member repository name
         * @param prefix Path prefix to match
         */
        public PathPrefix {
            Objects.requireNonNull(member, "member");
            Objects.requireNonNull(prefix, "prefix");
        }

        @Override
        public boolean matches(final String path) {
            final String normalized = path.startsWith("/") ? path.substring(1) : path;
            return normalized.startsWith(this.prefix);
        }
    }

    /**
     * Regex pattern-based routing rule.
     * Matches any path that matches the specified regex pattern.
     *
     * @param member Member repository name
     * @param regex Regex pattern string (e.g., "org/apache/.*")
     */
    record PathPattern(String member, String regex) implements RoutingRule {

        /**
         * Compiled pattern for efficient matching.
         */
        private static final java.util.concurrent.ConcurrentHashMap<String, Pattern> PATTERNS =
            new java.util.concurrent.ConcurrentHashMap<>();

        /**
         * Ctor.
         * @param member Member repository name
         * @param regex Regex pattern string
         */
        public PathPattern {
            Objects.requireNonNull(member, "member");
            Objects.requireNonNull(regex, "regex");
            // Pre-compile to catch invalid regex early
            PATTERNS.computeIfAbsent(regex, Pattern::compile);
        }

        @Override
        public boolean matches(final String path) {
            final String normalized = path.startsWith("/") ? path.substring(1) : path;
            return PATTERNS.computeIfAbsent(this.regex, Pattern::compile)
                .matcher(normalized)
                .matches();
        }
    }
}
