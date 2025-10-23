/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.group;
import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.RsStatus;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.jcabi.log.Logger;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Group/virtual repository slice.
 *
 * A read-only facade that tries member repositories in order and returns
 * the first non-404 response. If all members return 404, responds with 404.
 * Uploads are not supported and return 405.
 */
public final class GroupSlice implements Slice {

    /**
     * Repository slices resolver/cache.
     */
    private final SliceResolver resolver;

    /**
     * Group repository name.
     */
    private final String group;

    /**
     * Members in resolution priority order.
     */
    private final List<String> members;

    /**
     * Server port used for resolving member slices.
     */
    private final int port;

    /**
     * Ctor.
     *
     * @param resolver Slice resolver/cache
     * @param group Group repository name
     * @param members Member repository names in priority order
     * @param port Server port
     */
    public GroupSlice(final SliceResolver resolver, final String group, final List<String> members, final int port) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.group = Objects.requireNonNull(group, "group");
        this.members = Objects.requireNonNull(members, "members");
        this.port = port;
    }

    @Override
    public CompletableFuture<Response> response(final RequestLine line, final Headers headers, final Content body) {
        final String method = line.method().value();
        final String path = line.uri().getPath();
        
        // Allow read-only methods
        if ("GET".equals(method) || "HEAD".equals(method)) {
            return tryMember(0, line, headers, body);
        }
        
        // Allow POST for npm audit endpoints
        if ("POST".equals(method) && path.contains("/-/npm/v1/security/")) {
            return tryMember(0, line, headers, body);
        }
        
        // Allow PUT for npm adduser/login endpoints (only for npm-group)
        if ("PUT".equals(method) && this.isNpmGroup() && this.isNpmAuthEndpoint(path)) {
            // Forward to first member (typically the local npm repo)
            return tryMember(0, line, headers, body);
        }
        
        // Block all other write operations (PUT, DELETE, etc.)
        return ResponseBuilder.methodNotAllowed().completedFuture();
    }
    
    /**
     * Check if this is an npm-group repository.
     * @return True if group name indicates npm-group
     */
    private boolean isNpmGroup() {
        // Check if group name ends with _group (npm_group) or contains "npm"
        return this.group != null && 
               (this.group.contains("npm") || this.group.equals("npm-group"));
    }
    
    /**
     * Check if path is an npm authentication endpoint.
     * @param path Request path
     * @return True if this is npm adduser/login endpoint
     */
    private boolean isNpmAuthEndpoint(final String path) {
        return path.contains("/-/user/org.couchdb.user:");
    }

    private CompletableFuture<Response> tryMember(
        final int index,
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        if (index >= this.members.size()) {
            return ResponseBuilder.notFound().completedFuture();
        }
        final String member = this.members.get(index);
        final Slice memberSlice = this.resolver.slice(new Key.From(member), this.port);
        final RequestLine rewritten = rewrite(line, member);
        final Headers sanitized = dropFullPathHeader(headers);
        Logger.debug(this, "Group %s trying member %s: %s", this.group, member, rewritten.uri());
        return memberSlice.response(rewritten, sanitized, body).thenCompose(resp -> {
            Logger.debug(this, "Member %s responded %s for %s", member, resp.status(), rewritten.uri());
            if (resp.status() == RsStatus.NOT_FOUND) {
                // IMPORTANT: pass sanitized headers to the next member as well to avoid
                // TrimPathSlice recursion detection (X-FullPath) breaking path rewriting.
                return tryMember(index + 1, line, sanitized, body);
            }
            return CompletableFuture.completedFuture(resp);
        });
    }

    /**
     * Rewrite request line by prefixing member repo name to path.
     * Assumes the path is already trimmed (group name removed) by outer TrimPathSlice.
     *
     * @param original Original request line
     * @param member Member repository name to prefix
     * @return New request line
     */
    private static RequestLine rewrite(final RequestLine original, final String member) {
        final URI uri = original.uri();
        final String raw = uri.getRawPath();
        final String base = raw.startsWith("/") ? raw : "/" + raw;
        final String pref = "/" + member + "/";
        final String path = base.equals("/") ? "/" : base;
        final String combined = path.startsWith(pref)
            ? path
            : ("/" + member + (path.equals("/") ? "" : path));
        final StringBuilder full = new StringBuilder(combined);
        if (uri.getRawQuery() != null) {
            full.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            full.append('#').append(uri.getRawFragment());
        }
        return new RequestLine(original.method().value(), full.toString(), original.version());
    }

    private static Headers dropFullPathHeader(final Headers headers) {
        final String hdr = "X-FullPath";
        return new Headers(
            headers.asList().stream()
                .filter(h -> !h.getKey().equalsIgnoreCase(hdr))
                .toList()
        );
    }
}
