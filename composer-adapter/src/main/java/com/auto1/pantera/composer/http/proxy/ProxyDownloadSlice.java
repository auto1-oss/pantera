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
package com.auto1.pantera.composer.http.proxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.UriClientSlice;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.cooldown.CooldownInspector;
import com.auto1.pantera.cooldown.CooldownRequest;
import com.auto1.pantera.cooldown.CooldownResponses;
import com.auto1.pantera.cooldown.CooldownService;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;

import javax.json.Json;
import javax.json.JsonObject;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URI;
import java.time.Instant;

/**
 * Slice for downloading actual package zip files through proxy.
 * Emits events to database when packages are actually downloaded.
 *
 * @since 1.0
 */
public final class ProxyDownloadSlice implements Slice {

    /**
     * Pattern to match rewritten download URLs.
     * The repo prefix is stripped by TrimPathSlice, so path arrives as:
     * /dist/{vendor}/{package}/{version}.zip  (new format)
     * /dist/{vendor}/{package}/{version}      (legacy, no extension)
     */
    private static final Pattern DOWNLOAD_PATTERN = Pattern.compile(
        "^/dist/(?<vendor>[^/]+)/(?<package>[^/]+)/(?<version>.+?)(?:\\.zip)?$"
    );

    /**
     * Remote slice to fetch from (for same-host requests).
     */
    private final Slice remote;

    /**
     * HTTP clients for building dynamic slices per host.
     */
    private final ClientSlices clients;

    /**
     * Remote base URI (used to detect same-host downloads).
     */
    private final URI remoteBase;


    /**
     * Proxy artifact events queue.
     */
    private final Optional<Queue<ProxyArtifactEvent>> events;

    /**
     * Repository name.
     */
    private final String rname;
    
    /**
     * Repository type.
     */
    private final String rtype;
    
    /**
     * Storage to read cached metadata.
     */
    private final Storage storage;

    /**
     * Cooldown service.
     */
    private final CooldownService cooldown;

    /**
     * Cooldown inspector.
     */
    private final CooldownInspector inspector;

    /**
     * Ctor.
     *
     * @param remote Remote slice (AuthClientSlice over remoteBase)
     * @param clients HTTP clients
     * @param remoteBase Remote base URI
     * @param events Events queue
     * @param rname Repository name
     * @param rtype Repository type
     * @param storage Storage for reading cached metadata
     * @param cooldown Cooldown service
     * @param inspector Cooldown inspector
     */
    public ProxyDownloadSlice(
        final Slice remote,
        final ClientSlices clients,
        final URI remoteBase,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname,
        final String rtype,
        final Storage storage,
        final CooldownService cooldown,
        final CooldownInspector inspector
    ) {
        this.remote = remote;
        this.clients = clients;
        this.remoteBase = remoteBase;
        this.events = events;
        this.rname = rname;
        this.rtype = rtype;
        this.storage = storage;
        this.cooldown = cooldown;
        this.inspector = inspector;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        // GET requests should have empty body, but we must consume it to complete the request
        return body.asBytesFuture().thenCompose(ignored -> {
            final String path = line.uri().getPath();
            EcsLogger.info("com.auto1.pantera.composer")
                .message("ProxyDownloadSlice handling request")
                .eventCategory("web")
                .eventAction("proxy_download")
                .field("url.path", path)
                .field("http.request.method", line.method().value())
                .log();
            EcsLogger.debug("com.auto1.pantera.composer")
                .message("Full request URI")
                .eventCategory("web")
                .eventAction("proxy_download")
                .field("url.full", line.uri().toString())
                .log();

            // Extract package info from rewritten URL
            final Matcher matcher = DOWNLOAD_PATTERN.matcher(path);
            if (!matcher.matches()) {
                EcsLogger.warn("com.auto1.pantera.composer")
                    .message("URL doesn't match download pattern (expected pattern: /dist/vendor/package/version)")
                    .eventCategory("web")
                    .eventAction("proxy_download")
                    .eventOutcome("failure")
                    .field("url.path", path)
                    .log();
                // Still proxy to remote in case it's a valid request
                return this.remote.response(line, Headers.EMPTY, Content.EMPTY);
            }

            final String vendor = matcher.group("vendor");
            final String pkg = matcher.group("package");
            final String version = matcher.group("version");
            final String packageName = vendor + "/" + pkg;

            EcsLogger.info("com.auto1.pantera.composer")
                .message("Download request for package")
                .eventCategory("web")
                .eventAction("proxy_download")
                .field("package.name", packageName)
                .field("package.version", version)
                .log();

            // Evaluate cooldown before proceeding
            final String owner = new Login(headers).getValue();
            final CooldownRequest cdreq = new CooldownRequest(
                this.rtype,
                this.rname,
                packageName,
                version,
                owner,
                Instant.now()
            );

            // Cache-first: check local storage before network calls
            // New format uses .zip extension; also check legacy key without it
            final Key distKey = new Key.From(
                "dist", vendor, pkg, version + ".zip"
            );
            final Key legacyKey = new Key.From("dist", vendor, pkg, version);
            return this.storage.exists(distKey).thenCompose(cached -> {
                if (cached) {
                    return CompletableFuture.completedFuture(distKey);
                }
                // Fall back to legacy key (no .zip)
                return this.storage.exists(legacyKey).thenApply(
                    legacy -> legacy ? legacyKey : null
                );
            }).thenCompose(foundKey -> {
                if (foundKey != null) {
                    EcsLogger.info("com.auto1.pantera.composer")
                        .message("Cache HIT for dist artifact")
                        .eventCategory("web")
                        .eventAction("cache_hit")
                        .eventOutcome("success")
                        .field("package.name", packageName)
                        .field("package.version", version)
                        .log();
                    this.emitEvent(packageName, version, headers);
                    return this.storage.value(foundKey).thenApply(content ->
                        ResponseBuilder.ok()
                            .header("Content-Type", "application/zip")
                            .body(content)
                            .build()
                    );
                }
                // Cache miss — evaluate cooldown, then fetch from upstream
                return this.cooldown.evaluate(cdreq, this.inspector).thenCompose(result -> {
                    if (result.blocked()) {
                        EcsLogger.info("com.auto1.pantera.composer")
                            .message("Cooldown blocked download")
                            .eventCategory("web")
                            .eventAction("proxy_download")
                            .eventOutcome("failure")
                            .field("event.reason", "cooldown_active")
                            .field("package.name", packageName)
                            .field("package.version", version)
                            .log();
                        return CompletableFuture.completedFuture(
                            CooldownResponses.forbidden(result.block().orElseThrow())
                        );
                    }
                    return this.fetchAndCache(
                        line, headers, packageName, version, distKey
                    );
                });
            });
        });
    }

    /**
     * Fetch dist from upstream, cache to storage, then return response.
     */
    private CompletableFuture<Response> fetchAndCache(
        final RequestLine line,
        final Headers headers,
        final String packageName,
        final String version,
        final Key distKey
    ) {
        return this.findOriginalUrl(packageName, version).thenCompose(originalUrl -> {
            if (originalUrl.isEmpty()) {
                EcsLogger.error("com.auto1.pantera.composer")
                    .message("Could not find original URL for package")
                    .eventCategory("web")
                    .eventAction("proxy_download")
                    .eventOutcome("failure")
                    .field("package.name", packageName)
                    .field("package.version", version)
                    .log();
                return CompletableFuture.completedFuture(
                    ResponseBuilder.notFound().build()
                );
            }
            final String orig = originalUrl.get();
            final URI ouri = URI.create(orig);
            final Slice target;
            if (sameHost(this.remoteBase, ouri)) {
                target = this.remote;
            } else {
                target = new UriClientSlice(this.clients, baseOf(ouri));
            }
            final String pathWithQuery = buildPathWithQuery(ouri);
            final RequestLine newLine = RequestLine.from(
                line.method().value() + " " + pathWithQuery + " " + line.version()
            );
            final Headers out = buildUpstreamHeaders(headers);
            EcsLogger.debug("com.auto1.pantera.composer")
                .message("Fetching dist from upstream")
                .eventCategory("web")
                .eventAction("proxy_download")
                .field("url.original", orig)
                .log();
            return target.response(newLine, out, Content.EMPTY).thenCompose(response -> {
                if (!response.status().success()) {
                    EcsLogger.warn("com.auto1.pantera.composer")
                        .message("Upstream download failed")
                        .eventCategory("web")
                        .eventAction("proxy_download")
                        .eventOutcome("failure")
                        .field("package.name", packageName)
                        .field("package.version", version)
                        .field("http.response.status_code", response.status().code())
                        .log();
                    return CompletableFuture.completedFuture(response);
                }
                // Buffer content, save to storage, then return
                return response.body().asBytesFuture().thenCompose(bytes -> {
                    EcsLogger.info("com.auto1.pantera.composer")
                        .message("Caching dist artifact to storage")
                        .eventCategory("web")
                        .eventAction("proxy_download")
                        .eventOutcome("success")
                        .field("package.name", packageName)
                        .field("package.version", version)
                        .field("file.size", bytes.length)
                        .log();
                    return this.storage.save(
                        distKey, new Content.From(bytes)
                    ).thenApply(unused -> {
                        this.emitEvent(packageName, version, headers);
                        return ResponseBuilder.ok()
                            .header("Content-Type", "application/zip")
                            .body(new Content.From(bytes))
                            .build();
                    });
                });
            });
        });
    }

    /**
     * Build a minimal set of upstream headers.
     * Copies User-Agent from client if present; otherwise sets a default.
     * Adds a generic Accept header suitable for binary content.
     */
    private static Headers buildUpstreamHeaders(final Headers incoming) {
        final Headers out = new Headers();
        final java.util.List<com.auto1.pantera.http.headers.Header> ua = incoming.find("User-Agent");
        if (!ua.isEmpty()) {
            out.add(ua.getFirst(), true);
        } else {
            out.add("User-Agent", "Pantera-Composer-Proxy");
        }
        out.add("Accept", "application/octet-stream, */*");
        return out;
    }

    /**
     * Build base URI (scheme://host[:port]) for given URI.
     *
     * @param uri Input URI
     * @return Base URI
     */
    private static URI baseOf(final URI uri) {
        final int port = uri.getPort();
        final String auth = (port == -1)
            ? String.format("%s://%s", uri.getScheme(), uri.getHost())
            : String.format("%s://%s:%d", uri.getScheme(), uri.getHost(), port);
        return URI.create(auth);
    }

    /**
     * Build path with optional query for request line.
     *
     * @param uri URI
     * @return Path with query
     */
    private static String buildPathWithQuery(final URI uri) {
        final String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) ? "/" : uri.getRawPath();
        final String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) {
            return path;
        }
        return path + "?" + query;
    }

    /**
     * Check if two URIs point to the same host:port and scheme.
     *
     * @param a First URI
     * @param b Second URI
     * @return True if same scheme, host and port
     */
    private static boolean sameHost(final URI a, final URI b) {
        return safeEq(a.getScheme(), b.getScheme())
            && safeEq(a.getHost(), b.getHost())
            && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(final URI u) {
        final int p = u.getPort();
        if (p != -1) {
            return p;
        }
        final String scheme = u.getScheme();
        if ("https".equalsIgnoreCase(scheme)) {
            return 443;
        }
        if ("http".equalsIgnoreCase(scheme)) {
            return 80;
        }
        return -1;
    }

    private static boolean safeEq(final String s1, final String s2) {
        return s1 == null ? s2 == null : s1.equalsIgnoreCase(s2);
    }
    
    /**
     * Find original download URL from cached metadata.
     *
     * @param packageName Package name (vendor/package)
     * @param version Version
     * @return Original URL or empty
     */
    private CompletableFuture<Optional<String>> findOriginalUrl(
        final String packageName,
        final String version
    ) {
        // Metadata is cached by CachedProxySlice with .json extension
        final Key metadataKey = new Key.From(packageName + ".json");
        
        return this.storage.exists(metadataKey).thenCompose(exists -> {
            if (!exists) {
                EcsLogger.warn("com.auto1.pantera.composer")
                    .message("Metadata not found for package")
                    .eventCategory("web")
                    .eventAction("proxy_download")
                    .eventOutcome("failure")
                    .field("package.name", packageName)
                    .log();
                return CompletableFuture.completedFuture(Optional.empty());
            }
            
            return this.storage.value(metadataKey).thenCompose(content ->
                content.asBytesFuture().thenApply(bytes -> {
                    try {
                        final String json = new String(bytes, StandardCharsets.UTF_8);
                        final JsonObject metadata = Json.createReader(new StringReader(json)).readObject();
                        
                        final JsonObject packages = metadata.getJsonObject("packages");
                        if (packages == null) {
                            return Optional.empty();
                        }
                        final javax.json.JsonValue pkgVal = packages.get(packageName);
                        if (pkgVal == null) {
                            return Optional.empty();
                        }

                        JsonObject versionData = null;
                        if (pkgVal.getValueType() == javax.json.JsonValue.ValueType.ARRAY) {
                            final javax.json.JsonArray arr = pkgVal.asJsonArray();
                            for (javax.json.JsonValue v : arr) {
                                final JsonObject vo = v.asJsonObject();
                                final String vstr = vo.getString("version", "");
                                if (versionEquals(vstr, version)) {
                                    versionData = vo;
                                    break;
                                }
                            }
                        } else {
                            final JsonObject versions = pkgVal.asJsonObject();
                            versionData = versions.getJsonObject(version);
                            if (versionData == null) {
                                // try normalized key without leading 'v'
                                versionData = versions.getJsonObject(stripV(version));
                            }
                        }

                        if (versionData == null) {
                            return Optional.empty();
                        }

                        final JsonObject dist = versionData.getJsonObject("dist");
                        if (dist == null) {
                            return Optional.empty();
                        }

                        // Get original URL from cached metadata
                        // Cached file now has rewritten format with "original_url" field
                        // containing the actual remote URL (GitHub/packagist)
                        String originalUrl = null;
                        if (dist.containsKey("original_url")) {
                            originalUrl = dist.getString("original_url");
                            EcsLogger.info("com.auto1.pantera.composer")
                                .message("Using original_url from metadata")
                                .eventCategory("web")
                                .eventAction("proxy_download")
                                .field("package.name", packageName)
                                .field("package.version", version)
                                .field("url.original", originalUrl)
                                .log();
                        } else if (dist.containsKey("url")) {
                            // Fallback to "url" for backward compatibility
                            originalUrl = dist.getString("url");
                            EcsLogger.warn("com.auto1.pantera.composer")
                                .message("No original_url found in dist, using url field")
                                .eventCategory("web")
                                .eventAction("proxy_download")
                                .field("package.name", packageName)
                                .field("package.version", version)
                                .field("url.original", originalUrl)
                                .log();
                        }
                        if (originalUrl == null || originalUrl.isEmpty()) {
                            EcsLogger.warn("com.auto1.pantera.composer")
                                .message("No dist URL found for package")
                                .eventCategory("web")
                                .eventAction("proxy_download")
                                .eventOutcome("failure")
                                .field("package.name", packageName)
                                .field("package.version", version)
                                .log();
                            return Optional.empty();
                        }
                        EcsLogger.info("com.auto1.pantera.composer")
                            .message("Found original URL for package")
                            .eventCategory("web")
                            .eventAction("proxy_download")
                            .field("package.name", packageName)
                            .field("package.version", version)
                            .field("url.original", originalUrl)
                            .log();
                        return Optional.ofNullable(originalUrl);
                    } catch (Exception ex) {
                        EcsLogger.error("com.auto1.pantera.composer")
                            .message("Failed to parse metadata")
                            .eventCategory("web")
                            .eventAction("proxy_download")
                            .eventOutcome("failure")
                            .field("package.name", packageName)
                            .error(ex)
                            .log();
                        return Optional.empty();
                    }
                })
            );
        });
    }

    private static boolean versionEquals(final String a, final String b) {
        return stripV(a).equals(stripV(b));
    }

    private static String stripV(final String v) {
        if (v == null) {
            return "";
        }
        return v.startsWith("v") || v.startsWith("V") ? v.substring(1) : v;
    }

    /**
     * Emit event for downloaded package.
     *
     * @param packageName Package name
     * @param version Package version
     * @param headers Request headers
     */
    private void emitEvent(final String packageName, final String version, final Headers headers) {
        if (this.events.isEmpty()) {
            EcsLogger.debug("com.auto1.pantera.composer")
                .message("Events queue is empty, skipping event")
                .eventCategory("web")
                .eventAction("proxy_download")
                .field("package.name", packageName)
                .log();
            return;
        }
        final String owner = new Login(headers).getValue();
        // Store key as "packageName/version" so processor knows which version was downloaded
        final Key eventKey = new Key.From(packageName, version);
        this.events.get().add(
            new ProxyArtifactEvent(
                eventKey,
                this.rname,
                owner,
                Optional.empty()  // No release date from download
            )
        );
        EcsLogger.info("com.auto1.pantera.composer")
            .message("Emitted download event (queue size: " + this.events.get().size() + ")")
            .eventCategory("web")
            .eventAction("proxy_download")
            .eventOutcome("success")
            .field("package.name", packageName)
            .field("package.version", version)
            .field("user.name", owner)
            .log();
    }
}
