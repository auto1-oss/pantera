/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.adapters.docker;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.SubStorage;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.asto.AstoDocker;
import com.auto1.pantera.docker.asto.RegistryRoot;
import com.auto1.pantera.docker.cache.CacheDocker;
import com.auto1.pantera.docker.cache.DockerProxyCooldownInspector;
import com.auto1.pantera.docker.composite.MultiReadDocker;
import com.auto1.pantera.docker.composite.ReadWriteDocker;
import com.auto1.pantera.docker.http.DockerSlice;
import com.auto1.pantera.docker.http.TrimmedDocker;
import com.auto1.pantera.docker.proxy.ProxyDocker;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.publishdate.DbPublishDateRegistry;
import com.auto1.pantera.publishdate.PublishDateRegistries;
import com.auto1.pantera.http.auth.CombinedAuthScheme;
import com.auto1.pantera.http.DockerRoutingSlice;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.RemoteConfig;
import com.auto1.pantera.http.client.auth.AuthClientSlice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.repo.RepoConfig;

import com.auto1.pantera.http.log.EcsLogger;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

/**
 * Docker proxy slice created from config.
 *
 * @since 0.9
 */
public final class DockerProxy implements Slice {

    private final Slice delegate;

    /**
     * Ctor.
     *
     * @param client HTTP client.
     * @param cfg Repository configuration.
     * @param policy Access policy.
     * @param auth Authentication mechanism.
     * @param events Artifact events queue
     * @param cooldown Cooldown service
     */
    public DockerProxy(
        final ClientSlices client,
        final RepoConfig cfg,
        final Policy<?> policy,
        final Authentication auth,
        final TokenAuthentication tokens,
        final Optional<Queue<ArtifactEvent>> events,
        final CooldownService cooldown
    ) {
        this.delegate = createProxy(client, cfg, policy, auth, tokens, events, cooldown);
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final long start = System.currentTimeMillis();
        EcsLogger.info("com.auto1.pantera.docker.proxy")
            .message("DockerProxy request")
            .eventCategory("web")
            .eventAction("proxy_request")
            .field("http.request.method", line.method().value())
            .field("url.path", line.uri().getPath())
            .log();
        return this.delegate.response(line, headers, body)
            .whenComplete((resp, err) -> {
                final long duration = System.currentTimeMillis() - start;
                if (err != null) {
                    EcsLogger.error("com.auto1.pantera.docker.proxy")
                        .message("DockerProxy error")
                        .eventCategory("web")
                        .eventAction("proxy_request")
                        .eventOutcome("failure")
                        .field("url.path", line.uri().getPath())
                        .duration(duration)
                        .error(err)
                        .log();
                } else {
                    EcsLogger.info("com.auto1.pantera.docker.proxy")
                        .message("DockerProxy response")
                        .eventCategory("web")
                        .eventAction("proxy_request")
                        .eventOutcome(resp.status().success() ? "success" : "failure")
                        .field("url.path", line.uri().getPath())
                        .field("http.response.status_code", resp.status().code())
                        .duration(duration)
                        .log();
                }
            });
    }

    /**
     * Creates Docker proxy repository slice from configuration.
     *
     * @return Docker proxy slice.
     */
    private static Slice createProxy(
            final ClientSlices client,
            final RepoConfig cfg,
            final Policy<?> policy,
            final Authentication auth,
            final TokenAuthentication tokens,
            final Optional<Queue<ArtifactEvent>> events,
            final CooldownService cooldown
    ) {
        final DockerProxyCooldownInspector inspector = new DockerProxyCooldownInspector();
        final var registry = PublishDateRegistries.instance();
        if (registry instanceof DbPublishDateRegistry dbRegistry) {
            inspector.setReleaseDateCallback((artifact, version, release) ->
                dbRegistry.persist("docker", artifact, version, release, "manifest-config"));
        }
        final Docker proxies = new MultiReadDocker(
            cfg.remotes().stream().map(r -> proxy(client, cfg, events, r, inspector))
                .toList()
        );
        Docker docker = cfg.storageOpt()
            .<Docker>map(
                storage -> {
                    final AstoDocker local = new AstoDocker(
                        cfg.name(),
                        new SubStorage(RegistryRoot.V2, storage)
                    );
                    return new ReadWriteDocker(new MultiReadDocker(local, proxies), local);
                }
            )
            .orElse(proxies);
        docker = new TrimmedDocker(docker, cfg.name());
        Slice slice = new DockerSlice(
            docker, policy, new CombinedAuthScheme(auth, tokens), events
        );
        slice = new DockerProxyCooldownSlice(
            slice,
            cfg.name(),
            cfg.type(),
            cooldown,
            inspector,
            docker
        );
        if (cfg.port().isEmpty()) {
            slice = new DockerRoutingSlice.Reverted(slice);
        }
        return slice;
    }

    /**
     * Create proxy from YAML config.
     *
     * @param remote YAML remote config.
     * @return Docker proxy.
     */
    private static Docker proxy(
            final ClientSlices client,
            final RepoConfig cfg,
            final Optional<Queue<ArtifactEvent>> events,
            final RemoteConfig remote,
            final DockerProxyCooldownInspector inspector
    ) {
        final Docker proxy = new ProxyDocker(
            cfg.name(),
            AuthClientSlice.withClientSlice(client, remote),
            remote.uri()
        );
        return cfg.storageOpt().<Docker>map(
            cache -> new CacheDocker(
                proxy,
                new AstoDocker(cfg.name(), new SubStorage(RegistryRoot.V2, cache)),
                events,
                Optional.of(inspector),
                remote.uri().toString()  // Pass upstream URL for metrics
            )
        ).orElse(proxy);
    }
}
