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
package com.auto1.pantera.http;

import java.util.Locale;

/**
 * Realistic ecosystem-native {@code User-Agent} strings used by Pantera when
 * issuing upstream requests.
 *
 * <p>Public package registries (Maven Central, registry.npmjs.org, PyPI,
 * proxy.golang.org, packagist.org, rubygems.org) bin requests by their
 * {@code User-Agent} for rate-limit accounting and bot-detection. Identifying
 * Pantera explicitly there causes two problems at scale:
 * <ol>
 *   <li>Every Pantera install on the public internet shares one UA bucket,
 *       so as adoption grows the bucket is more likely to hit the per-UA
 *       throttle than any individual operator deserves.</li>
 *   <li>Some registries return {@code 403} or rate-limit hard for known-bot
 *       UAs while leaving native-tool UAs unrestricted; we want to look
 *       like the native tool that an end user would have run anyway.</li>
 * </ol>
 *
 * <p>The strings here are sampled from current default ecosystem clients.
 * They're versioned conservatively (not bleeding-edge) so registries that
 * happen to enforce minimum-tool-version policies still accept them.
 *
 * @since 2.2.0
 */
public final class EcosystemUserAgents {

    /** Go's stdlib HTTP client. {@code go mod / go get} send exactly this. */
    public static final String GO = "Go-http-client/1.1";

    /** npm CLI default UA shape: {@code npm/<v> node/<v> <os> <arch> ...}. */
    public static final String NPM =
        "npm/10.5.0 node/v20.11.1 linux x64 workspaces/false";

    /** pip default UA shape (canonical form, JSON metadata stripped). */
    public static final String PIP = "pip/24.0";

    /** Apache Maven (resolver) default UA. */
    public static final String MAVEN =
        "Apache-Maven/3.9.6 (Java 21.0.2; Linux 6.6.10)";

    /** Composer default UA shape. */
    public static final String COMPOSER =
        "Composer/2.7.1 (Linux; 6.6.10; PHP 8.3.3; cURL 8.5.0)";

    /** Bundler / RubyGems default UA shape. */
    public static final String BUNDLER =
        "bundler/2.5.6 rubygems/3.5.6 ruby/3.3.0 (x86_64-linux) command/install";

    /** Helm CLI default UA shape. */
    public static final String HELM = "Helm/3.14.0";

    /** Docker CLI / containerd resolver default UA. */
    public static final String DOCKER = "containerd/1.7.13";

    /** Apt / Debian package manager UA shape. */
    public static final String APT = "Debian APT-HTTP/1.3 (2.7.10)";

    /** Hex (Erlang/Elixir) package manager UA. */
    public static final String HEX = "Hex/2.0.6 (Elixir/1.16.1)";

    private EcosystemUserAgents() {
    }

    /**
     * Pick a realistic native-tool {@code User-Agent} for the given repository
     * type, used when the inbound client request did not send one (rare but
     * possible — some custom clients omit the header).
     *
     * <p>Falls back to the Go UA when the repo type is unrecognised, since
     * {@code Go-http-client/1.1} is generic enough to look like a vanilla
     * Go HTTP call rather than identifying any specific bot.
     *
     * @param repoType Adapter type, e.g. {@code "maven"}, {@code "npm-proxy"}
     * @return realistic ecosystem-native User-Agent
     */
    public static String defaultFor(final String repoType) {
        if (repoType == null) {
            return GO;
        }
        final String type = repoType.toLowerCase(Locale.ROOT);
        if (type.startsWith("maven") || type.startsWith("gradle")) {
            return MAVEN;
        }
        if (type.startsWith("npm")) {
            return NPM;
        }
        if (type.startsWith("pypi")) {
            return PIP;
        }
        if (type.startsWith("go")) {
            return GO;
        }
        if (type.startsWith("composer") || type.startsWith("php")) {
            return COMPOSER;
        }
        if (type.startsWith("gem") || type.startsWith("ruby")) {
            return BUNDLER;
        }
        if (type.startsWith("helm")) {
            return HELM;
        }
        if (type.startsWith("docker")) {
            return DOCKER;
        }
        if (type.startsWith("debian") || type.startsWith("apt")) {
            return APT;
        }
        if (type.startsWith("hex")) {
            return HEX;
        }
        return GO;
    }
}
