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
package com.auto1.pantera.http.client.auth;

import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.LogThrottle;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The record of upstream credentials that were not sent to a host
 * {@link RealmTrust} does not trust: a bearer-token realm or a mirror that
 * is neither the upstream host nor in {@code upstream_credential_allow_hosts}.
 *
 * <p>The decision is taken on every request and stays the same until an
 * operator changes the allowlist, so the record is written for the first
 * occurrence per (upstream host, untrusted host) and then at most once an
 * hour, with the number of occurrences in between. It tells the operator
 * which setting releases the credentials.</p>
 *
 * @since 2.2.14
 */
public final class WithheldCredentialsLog {

    /**
     * Admin setting that lists the hosts credentials may be sent to.
     */
    public static final String SETTING = "upstream_credential_allow_hosts";

    /**
     * Shared by every upstream: one record per pair of hosts and hour,
     * whichever repository or client instance sees the host.
     */
    private static final LogThrottle SHARED = new LogThrottle();

    /**
     * Logger name.
     */
    private final String logger;

    /**
     * ECS event.action of the record.
     */
    private final String action;

    /**
     * What the untrusted host is, for the message: "Bearer realm", "Mirror".
     */
    private final String kind;

    /**
     * Which occurrences are logged.
     */
    private final LogThrottle throttle;

    /**
     * Ctor with the process-wide throttle.
     * @param logger Logger name
     * @param action ECS event.action
     * @param kind What the untrusted host is, capitalised ("Bearer realm")
     */
    public WithheldCredentialsLog(final String logger, final String action, final String kind) {
        this(logger, action, kind, WithheldCredentialsLog.SHARED);
    }

    /**
     * Primary ctor.
     * @param logger Logger name
     * @param action ECS event.action
     * @param kind What the untrusted host is, capitalised ("Bearer realm")
     * @param throttle Which occurrences are logged
     */
    public WithheldCredentialsLog(
        final String logger, final String action, final String kind, final LogThrottle throttle
    ) {
        this.logger = logger;
        this.action = action;
        this.kind = kind;
        this.throttle = throttle;
    }

    /**
     * Credentials of {@code upstream} were not sent to {@code target}.
     *
     * @param upstream Host of the configured upstream, or null when unknown
     * @param target URL on the untrusted host
     * @param consequence What happens instead, as a clause after a colon
     * @return The record when this occurrence is to be logged (the caller
     *  adds its own fields and calls {@code log()}), empty to skip it
     */
    public Optional<EcsLogger> record(
        final String upstream, final URI target, final String consequence
    ) {
        final String host = target.getHost() == null
            ? "unknown" : target.getHost().toLowerCase(Locale.ROOT);
        final String origin = upstream == null ? "unknown" : upstream;
        final OptionalLong skipped = this.throttle.admit(
            this.action + '|' + origin + '|' + host
        );
        Optional<EcsLogger> result = Optional.empty();
        if (skipped.isPresent()) {
            final StringBuilder text = new StringBuilder(320)
                .append(this.kind).append(" host ").append(host)
                .append(" is not the upstream host ").append(origin)
                .append(" and is not listed in ").append(WithheldCredentialsLog.SETTING)
                .append(": ").append(consequence)
                .append(". To send the configured credentials there, add ").append(host)
                .append(" to the admin setting ").append(WithheldCredentialsLog.SETTING)
                .append(" (Settings > Outbound Egress Policy; environment fallback")
                .append(" PANTERA_UPSTREAM_CREDENTIAL_ALLOW_HOSTS).");
            if ("auth.docker.io".equals(host)) {
                text.append(" Authenticated pulls from Docker Hub need auth.docker.io in that list.");
            }
            text.append(" Logged once per upstream and host, then at most hourly");
            if (skipped.getAsLong() > 0L) {
                text.append(" (").append(skipped.getAsLong())
                    .append(" more occurrence(s) since the previous record)");
            }
            result = Optional.of(
                EcsLogger.warn(this.logger)
                    .message(text.toString())
                    .eventAction(this.action)
                    .field("url.full", target.toString())
                    .field("destination.address", host)
                    .field("log.source", "application")
            );
        }
        return result;
    }
}
