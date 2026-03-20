/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.npm;

import com.auto1.pantera.PanteraException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The relative path of a .tgz uploaded archive.
 *
 * @since 0.3
 */
public final class TgzRelativePath {

    /**
     * Pattern for npm package name or scope name, the rules can be found
     * https://github.com/npm/validate-npm-package-name
     * https://docs.npmjs.com/cli/v8/using-npm/scope.
     */
    private static final String NAME = "[\\w][\\w._-]*";

    /**
     * Regex pattern for extracting version from package name.
     */
    private static final Pattern VRSN = Pattern.compile(".*(\\d+.\\d+.\\d+[-.\\w]*).tgz");

    /**
     * The full path.
     */
    private final String full;

    /**
     * Ctor.
     * @param full The full path.
     */
    public TgzRelativePath(final String full) {
        this.full = full;
    }

    /**
     * Extract the relative path.
     *
     * @return The relative path.
     */
    public String relative() {
        return this.relative(false);
    }
    
    /**
     * Strips absolute URL prefix if present, keeping only the path portion.
     * Handles URLs like: http://host:port/path/to/package.tgz -> /path/to/package.tgz
     * @param path The potentially absolute URL
     * @return Path without protocol and host
     */
    private String stripAbsoluteUrl(final String path) {
        // Check if it's an absolute URL (starts with http:// or https://)
        if (path.startsWith("http://") || path.startsWith("https://")) {
            try {
                final java.net.URI uri = new java.net.URI(path);
                // Return the path portion (which includes leading /)
                return uri.getPath();
            } catch (final java.net.URISyntaxException ex) {
                // If parsing fails, try simple string manipulation
                final int pathStart = path.indexOf('/', path.indexOf("://") + 3);
                if (pathStart > 0) {
                    return path.substring(pathStart);
                }
            }
        }
        return path;
    }

    /**
     * Extract the relative path.
     * @param replace Is it necessary to replace `/-/` with `/version/`
     *  in the path. It could be required for some cases.
     *  See <a href="https://www.jfrog.com/confluence/display/BT/npm+Repositories">
     *  Deploying with cURL</a> section.
     * @return The relative path.
     */
    public String relative(final boolean replace) {
        final Matched matched = this.matchedValues();
        final String res;
        if (replace) {
            final Matcher matcher = TgzRelativePath.VRSN.matcher(matched.name());
            if (!matcher.matches()) {
                throw new PanteraException(
                    String.format(
                        "Failed to replace `/-/` in path `%s` with name `%s`",
                        matched.group(),
                        matched.name()
                    )
                );
            }
            res = matched.group()
                .replace("/-/", String.format("/%s/", matcher.group(1)));
        } else {
            res = matched.group();
        }
        return res;
    }

    /**
     * Applies different patterns depending on type of uploading and
     * scope's presence.
     * @return Matched values.
     */
    private Matched matchedValues() {
        // Strip absolute URL prefix first if present
        final String pathToMatch = this.stripAbsoluteUrl(this.full);
        
        final Optional<Matched> npms = this.npmWithScope(pathToMatch);
        final Optional<Matched> npmws = this.npmWithoutScope(pathToMatch);
        final Optional<Matched> curls = this.curlWithScope(pathToMatch);
        final Optional<Matched> curlws = this.curlWithoutScope(pathToMatch);
        final Matched matched;
        if (npms.isPresent()) {
            matched = npms.get();
        } else if (curls.isPresent()) {
            matched = curls.get();
        } else if (npmws.isPresent()) {
            matched = npmws.get();
        } else if (curlws.isPresent()) {
            matched = curlws.get();
        } else {
            throw new PanteraException(
                String.format("a relative path was not found for: %s", this.full)
            );
        }
        return matched;
    }

    /**
     * Try to extract npm scoped path.
     * @param path Path to match against
     * @return The npm scoped path if found.
     */
    private Optional<Matched> npmWithScope(final String path) {
        return this.matches(
            path,
            Pattern.compile(
                String.format(
                    "(@%s/%s/-/@%s/(?<name>%s.tgz)$)", TgzRelativePath.NAME, TgzRelativePath.NAME,
                    TgzRelativePath.NAME, TgzRelativePath.NAME
                )
            )
        );
    }

    /**
     * Try to extract npm path without scope.
     * @param path Path to match against
     * @return The npm scoped path if found.
     */
    private Optional<Matched> npmWithoutScope(final String path) {
        return this.matches(
            path,
            Pattern.compile(
                String.format(
                    "(%s/-/(?<name>%s.tgz)$)", TgzRelativePath.NAME, TgzRelativePath.NAME
                )
            )
        );
    }

    /**
     * Try to extract a curl scoped path.
     * @param path Path to match against
     * @return The npm scoped path if found.
     */
    private Optional<Matched> curlWithScope(final String path) {
        return this.matches(
            path,
            Pattern.compile(
                String.format(
                    "(@%s/%s/(?<name>(@?(?<!-/@)[\\w._-]+/)*%s.tgz)$)",
                    TgzRelativePath.NAME, TgzRelativePath.NAME, TgzRelativePath.NAME
                )
            )
        );
    }

    /**
     * Try to extract a curl path without scope. Curl like
     *
     * http://10.40.149.70:8080/test_prefix/echo-test-npmrepo-Oze0nuvAiD/ssh2//-/ssh2-0.8.9.tgz
     *
     * should also be processed exactly as they are with this regex.
     * @param path Path to match against
     * @return The npm scoped path if found.
     */
    private Optional<Matched> curlWithoutScope(final String path) {
        return this.matches(
            path,
            Pattern.compile(
                "([\\w._-]+(/\\d+.\\d+.\\d+[\\w.-]*)?/(?<name>[\\w._-]+\\.tgz)$)"
            )
        );
    }

    /**
     * Find fist group match if found.
     * @param path Path to match against
     * @param pattern The pattern to match against.
     * @return The group from matcher and name if found.
     */
    private Optional<Matched> matches(final String path, final Pattern pattern) {
        final Matcher matcher = pattern.matcher(path);
        final boolean found = matcher.find();
        final Optional<Matched> result;
        if (found) {
            result = Optional.of(
                new Matched(matcher.group(1), matcher.group("name"))
            );
        } else {
            result = Optional.empty();
        }
        return result;
    }

    /**
     * Contains matched values which were obtained from regex.
     * @since 0.9
     */
    private static final class Matched {
        /**
         * Group from matcher.
         */
        private final String fgroup;

        /**
         * Group `name` from matcher.
         */
        private final String cname;

        /**
         * Ctor.
         * @param fgroup Group from matcher
         * @param name Group `name` from matcher
         */
        Matched(final String fgroup, final String name) {
            this.fgroup = fgroup;
            this.cname = name;
        }

        /**
         * Name.
         * @return Name from matcher.
         */
        public String name() {
            return this.cname;
        }

        /**
         * Group.
         * @return Group from matcher.
         */
        public String group() {
            return this.fgroup;
        }
    }
}
