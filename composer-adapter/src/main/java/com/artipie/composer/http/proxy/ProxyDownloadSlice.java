/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.composer.http.proxy;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.client.ClientSlices;
import com.artipie.http.client.UriClientSlice;
import com.artipie.http.headers.Login;
import com.artipie.http.rq.RequestLine;
import com.artipie.cooldown.CooldownInspector;
import com.artipie.cooldown.CooldownRequest;
import com.artipie.cooldown.CooldownResponses;
import com.artipie.cooldown.CooldownService;
import com.artipie.scheduling.ProxyArtifactEvent;
import com.jcabi.log.Logger;

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
     * The repo prefix is stripped by TrimPathSlice, so path arrives as: /dist/{vendor}/{package}/{version}
     */
    private static final Pattern DOWNLOAD_PATTERN = Pattern.compile(
        "^/dist/(?<vendor>[^/]+)/(?<package>[^/]+)/(?<version>.+)$"
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
        final String path = line.uri().getPath();
        Logger.info(this, "ProxyDownloadSlice handling: %s (method: %s)", path, line.method().value());
        Logger.debug(this, "Full request URI: %s", line.uri().toString());

        // Extract package info from rewritten URL
        final Matcher matcher = DOWNLOAD_PATTERN.matcher(path);
        if (!matcher.matches()) {
            Logger.warn(
                this, 
                "URL doesn't match download pattern: %s (expected pattern: /dist/vendor/package/version)",
                path
            );
            // Still proxy to remote in case it's a valid request
            return this.remote.response(line, Headers.EMPTY, Content.EMPTY);
        }

        final String vendor = matcher.group("vendor");
        final String pkg = matcher.group("package");
        final String version = matcher.group("version");
        final String packageName = vendor + "/" + pkg;

        Logger.info(this, "Download request for %s:%s", packageName, version);

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

        return this.cooldown.evaluate(cdreq, this.inspector).thenCompose(result -> {
            if (result.blocked()) {
                Logger.info(this, "Cooldown BLOCKED download for %s:%s", packageName, version);
                return CompletableFuture.completedFuture(
                    CooldownResponses.forbidden(result.block().orElseThrow())
                );
            }

            // Look up original URL from cached metadata
            return this.findOriginalUrl(packageName, version).thenCompose(originalUrl -> {
                if (originalUrl.isEmpty()) {
                    Logger.error(this, "Could not find original URL for %s:%s", packageName, version);
                    return CompletableFuture.completedFuture(
                        ResponseBuilder.notFound().build()
                    );
                }

                final String orig = originalUrl.get();
                Logger.info(this, "Fetching from original URL: %s", orig);

                final URI ouri = URI.create(orig);
            // Decide which slice to use: same-host -> reuse remote; other host -> build dynamic slice
            final Slice target;
            if (sameHost(this.remoteBase, ouri)) {
                target = this.remote;
            } else {
                // Build client for target host without applying remote auth
                target = new UriClientSlice(this.clients, baseOf(ouri));
            }

            // Build path+query request line for target host
            final String pathWithQuery = buildPathWithQuery(ouri);
            final RequestLine newLine = RequestLine.from(
                line.method().value() + " " + pathWithQuery + " " + line.version()
            );

            // Prepare minimal safe headers for upstream
            final Headers out = buildUpstreamHeaders(headers);

            // Fetch from original URL using chosen slice
            Logger.debug(this, "Proxying to %s%s with headers: %s", baseOf(ouri), pathWithQuery, out.asString());
            return target.response(newLine, out, Content.EMPTY).thenApply(response -> {
                if (response.status().success()) {
                    Logger.info(this, "Successfully downloaded %s:%s", packageName, version);
                    this.emitEvent(packageName, version, headers);
                } else {
                    Logger.warn(this, "Download failed for %s:%s: %s", packageName, version, response.status());
                }
                return response;
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
        final java.util.List<com.artipie.http.headers.Header> ua = incoming.find("User-Agent");
        if (!ua.isEmpty()) {
            out.add(ua.getFirst(), true);
        } else {
            out.add("User-Agent", "Artipie-Composer-Proxy");
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
        // Metadata is stored directly in repo root as {packageName}.json
        final Key metadataKey = new Key.From(packageName + ".json");
        
        return this.storage.exists(metadataKey).thenCompose(exists -> {
            if (!exists) {
                Logger.warn(this, "Metadata not found for %s", packageName);
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

                        // Prefer original dist.url from cached metadata (pre-rewrite).
                        String originalUrl = dist.getString("url", null);
                        if (originalUrl == null) {
                            // Fallback to reference if present
                            originalUrl = dist.getString("reference", null);
                        }
                        Logger.debug(this, "Found original URL for %s:%s = %s", packageName, version, originalUrl);
                        return Optional.ofNullable(originalUrl);
                    } catch (Exception ex) {
                        Logger.error(this, "Failed to parse metadata for %s: %[exception]s", packageName, ex);
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
            Logger.debug(this, "Events queue is empty, skipping event for %s", packageName);
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
        Logger.info(
            this,
            "Emitted download event for %s:%s (owner=%s, queue_size=%d)",
            packageName,
            version,
            owner,
            this.events.get().size()
        );
    }
}
