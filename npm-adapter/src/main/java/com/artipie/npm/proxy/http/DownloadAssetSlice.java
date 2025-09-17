/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.proxy.http;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.headers.ContentType;
import com.artipie.http.headers.Login;
import com.artipie.http.rq.RequestLine;
import com.artipie.npm.misc.DateTimeNowStr;
import com.artipie.npm.proxy.NpmProxy;
import com.artipie.scheduling.ProxyArtifactEvent;
import com.google.common.base.Strings;
import hu.akarnokd.rxjava2.interop.SingleInterop;

import com.artipie.cooldown.CooldownInspector;
import com.artipie.cooldown.CooldownRequest;
import com.artipie.cooldown.CooldownResponses;
import com.artipie.cooldown.CooldownResult;
import com.artipie.cooldown.CooldownService;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.time.Instant;

/**
 * HTTP slice for download asset requests.
 */
public final class DownloadAssetSlice implements Slice {
    /**
     * NPM Proxy facade.
     */
    private final NpmProxy npm;

    /**
     * Asset path helper.
     */
    private final AssetPath path;

    /**
     * Queue with packages and owner names.
     */
    private final Optional<Queue<ProxyArtifactEvent>> packages;

    /**
     * Repository name.
     */
    private final String repoName;

    /**
     * Repository type.
     */
    private final String repoType;

    /**
     * Cooldown service.
     */
    private final CooldownService cooldown;

    /**
     * Cooldown inspector.
     */
    private final CooldownInspector inspector;

    /**
     * @param npm NPM Proxy facade
     * @param path Asset path helper
     * @param packages Queue with proxy packages and owner
     * @param repoName Repository name
     * @param repoType Repository type
     * @param cooldown Cooldown service
     * @param inspector Cooldown inspector
     */
    public DownloadAssetSlice(final NpmProxy npm, final AssetPath path,
        final Optional<Queue<ProxyArtifactEvent>> packages, final String repoName,
        final String repoType, final CooldownService cooldown, final CooldownInspector inspector) {
        this.npm = npm;
        this.path = path;
        this.packages = packages;
        this.repoName = repoName;
        this.repoType = repoType;
        this.cooldown = cooldown;
        this.inspector = inspector;
    }

    @Override
    public CompletableFuture<Response> response(final RequestLine line,
                                                final Headers rqheaders,
                                                final Content body) {
        final String tgz = this.path.value(line.uri().getPath());
        final Optional<CooldownRequest> request = this.cooldownRequest(tgz, rqheaders);
        if (request.isEmpty()) {
            return this.serveAsset(tgz, rqheaders);
        }
        return this.cooldown.evaluate(request.get(), this.inspector)
            .thenCompose(result -> {
                if (result.blocked()) {
                    return CompletableFuture.completedFuture(
                        CooldownResponses.forbidden(result.block().orElseThrow())
                    );
                }
                return this.serveAsset(tgz, rqheaders);
            });
    }

    private CompletableFuture<Response> serveAsset(final String tgz, final Headers headers) {
        return this.npm.getAsset(tgz).map(
                asset -> {
                    this.packages.ifPresent(
                        queue -> queue.add(
                            new ProxyArtifactEvent(
                                new Key.From(tgz), this.repoName,
                                new Login(headers).getValue()
                            )
                        )
                    );
                    return asset;
                })
            .map(
                asset -> {
                    String mime = asset.meta().contentType();
                    if (Strings.isNullOrEmpty(mime)){
                        throw new IllegalStateException("Failed to get 'Content-Type'");
                    }
                    String lastModified = asset.meta().lastModified();
                    if(Strings.isNullOrEmpty(lastModified)){
                        lastModified = new DateTimeNowStr().value();
                    }
                    return ResponseBuilder.ok()
                        .header(ContentType.mime(mime))
                        .header("Last-Modified", lastModified)
                        .body(asset.dataPublisher())
                        .build();
                }
            )
            .toSingle(ResponseBuilder.notFound().build())
            .to(SingleInterop.get())
            .toCompletableFuture();
    }

    private Optional<CooldownRequest> cooldownRequest(final String original, final Headers headers) {
        final String decoded = URLDecoder.decode(original, StandardCharsets.UTF_8);
        final int sep = decoded.indexOf("/-/");
        if (sep < 0) {
            return Optional.empty();
        }
        final String pkg = decoded.substring(0, sep);
        final String file = decoded.substring(decoded.lastIndexOf('/') + 1);
        if (!file.endsWith(".tgz")) {
            return Optional.empty();
        }
        final String base = file.substring(0, file.length() - 4);
        final int dash = base.lastIndexOf('-');
        if (dash < 0) {
            return Optional.empty();
        }
        final String version = base.substring(dash + 1);
        if (version.isEmpty()) {
            return Optional.empty();
        }
        final String user = new Login(headers).getValue();
        return Optional.of(
            new CooldownRequest(
                this.repoType,
                this.repoName,
                pkg,
                version,
                user,
                Instant.now()
            )
        );
    }
}
