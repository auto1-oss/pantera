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
package com.auto1.pantera.api.v1;

import com.auto1.pantera.http.client.egress.EgressPolicy;
import com.auto1.pantera.http.log.EcsLogger;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonValue;

/**
 * Validates every outbound URL a repository-config writer can persist —
 * proxy {@code remotes[].url} and storage-alias endpoints — against the
 * {@link EgressPolicy} before it is saved.
 *
 * <p>Before 2.2.9 only the client-facing {@code repo.url} was checked;
 * {@code remotes[].url} went straight from the request body to
 * {@code RemoteConfig}, so a principal with repository CREATE/UPDATE could
 * point a proxy at {@code 169.254.169.254}, a link-local address or a
 * non-http scheme and have Pantera fetch it server-side on the next read.
 * The Jetty-level resolver guard is the last line of defence; refusing the
 * config at the boundary keeps a misconfigured repository from ever
 * existing.</p>
 *
 * <p>Two passes because config writes start on the event loop: a
 * synchronous pass (scheme/host syntax, literal IPs, metadata hostnames —
 * no DNS) and a resolving pass meant for the worker thread that performs
 * the save.</p>
 *
 * @since 2.2.9
 */
public final class RemoteUrlPolicy {

    /**
     * Egress policy.
     */
    private final Supplier<EgressPolicy> policy;

    /**
     * Ctor.
     *
     * @param policy Egress policy
     */
    public RemoteUrlPolicy(final EgressPolicy policy) {
        this(() -> policy);
    }

    /**
     * Ctor reading the policy through a supplier on every check, so an
     * admin edit applies to the next repository write.
     * @param policy Live policy source
     */
    public RemoteUrlPolicy(final Supplier<EgressPolicy> policy) {
        this.policy = policy;
    }

    /**
     * Policy following the DB-backed admin setting (environment fallback),
     * via http-client's {@link com.auto1.pantera.http.client.egress.EgressSettingsRegistry}.
     * @return Live policy
     */
    public static RemoteUrlPolicy fromRegistry() {
        return new RemoteUrlPolicy(
            com.auto1.pantera.http.client.egress.EgressSettingsRegistry.policy()
        );
    }

    /**
     * Synchronous pass (no DNS): every URL must be absolute http(s) with a
     * host, and neither a literal denied address nor a metadata hostname.
     *
     * @param urls Candidate outbound URLs
     * @return Error message for the first rejected URL, or empty
     */
    public Optional<String> syntaxError(final List<String> urls) {
        for (final String raw : urls) {
            final URI uri;
            try {
                uri = new URI(raw);
            } catch (final URISyntaxException ex) {
                return Optional.of(RemoteUrlPolicy.malformed(raw));
            }
            final String scheme = uri.getScheme();
            final boolean http = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            if (!http || uri.getHost() == null || uri.getHost().isBlank()) {
                return Optional.of(RemoteUrlPolicy.malformed(raw));
            }
            final Optional<String> byName = this.policy.get().hostRejection(uri.getHost());
            if (byName.isPresent()) {
                return Optional.of(this.denied(raw, byName.get()));
            }
            final Optional<InetAddress> literal = RemoteUrlPolicy.literal(uri.getHost());
            if (literal.isPresent()) {
                final Optional<String> rejection = this.policy.get().rejection(uri.getHost(), literal.get());
                if (rejection.isPresent()) {
                    return Optional.of(this.denied(raw, rejection.get()));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Resolving pass — BLOCKS on DNS; call only from a worker thread. A host
     * that does not resolve passes (the eventual fetch fails on its own);
     * a host that resolves only into denied ranges is refused.
     *
     * @param urls Candidate outbound URLs (already syntax-checked)
     * @return Error message for the first rejected URL, or empty
     */
    public Optional<String> resolvedError(final List<String> urls) {
        for (final String raw : urls) {
            final String host = URI.create(raw).getHost();
            if (RemoteUrlPolicy.literal(host).isPresent()) {
                continue;
            }
            final InetAddress[] addresses;
            try {
                addresses = InetAddress.getAllByName(host);
            } catch (final UnknownHostException ex) {
                continue;
            }
            String reason = null;
            boolean anyAllowed = false;
            for (final InetAddress address : addresses) {
                final Optional<String> rejection = this.policy.get().rejection(host, address);
                if (rejection.isPresent()) {
                    reason = rejection.get();
                } else {
                    anyAllowed = true;
                }
            }
            if (!anyAllowed && reason != null) {
                return Optional.of(this.denied(raw, reason));
            }
        }
        return Optional.empty();
    }

    /**
     * Collect every outbound URL from a repository config body: the
     * {@code remotes[].url} entries.
     *
     * @param repo The {@code repo} object of the request body
     * @return Outbound URLs (may be empty)
     */
    public static List<String> remoteUrls(final JsonObject repo) {
        final List<String> urls = new ArrayList<>();
        if (repo != null && repo.containsKey("remotes")
            && repo.get("remotes").getValueType() == JsonValue.ValueType.ARRAY) {
            final JsonArray remotes = repo.getJsonArray("remotes");
            for (int idx = 0; idx < remotes.size(); idx = idx + 1) {
                final JsonValue entry = remotes.get(idx);
                if (entry.getValueType() == JsonValue.ValueType.OBJECT) {
                    final JsonObject remote = entry.asJsonObject();
                    if (remote.containsKey("url")
                        && remote.get("url").getValueType() == JsonValue.ValueType.STRING) {
                        urls.add(remote.getString("url"));
                    } else {
                        urls.add("");
                    }
                }
            }
        }
        return urls;
    }

    private String denied(final String raw, final String reason) {
        EcsLogger.warn("com.auto1.pantera.api")
            .message("Outbound URL refused at config write: " + reason)
            .eventCategory("configuration")
            .eventAction("remote_url_rejected")
            .eventOutcome("failure")
            .field("url.full", raw)
            .field("event.reason", reason)
            .field("log.source", "application")
            .log();
        return String.format("remote url '%s' is not allowed: %s", raw, reason);
    }

    private static String malformed(final String raw) {
        return String.format(
            "remote url '%s' must be an absolute http(s) URL with a host", raw
        );
    }

    /**
     * Parse an IP literal without DNS.
     *
     * @param host Host text
     * @return The address when the host is a literal IP, else empty
     */
    private static Optional<InetAddress> literal(final String host) {
        if (host == null) {
            return Optional.empty();
        }
        final String bare = host.startsWith("[") && host.endsWith("]")
            ? host.substring(1, host.length() - 1) : host;
        final boolean looksLiteral = bare.indexOf(':') >= 0
            || bare.chars().allMatch(c -> Character.isDigit(c) || c == '.');
        if (!looksLiteral) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByName(bare));
        } catch (final UnknownHostException ex) {
            return Optional.empty();
        }
    }
}
