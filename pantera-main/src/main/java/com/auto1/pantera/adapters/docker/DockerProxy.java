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
import com.auto1.pantera.docker.cache.ManifestCopies;
import com.auto1.pantera.cooldown.CooldownLinkedVersions;
import com.auto1.pantera.docker.composite.MultiReadDocker;
import com.auto1.pantera.docker.composite.ReadWriteDocker;
import com.auto1.pantera.docker.cooldown.CooldownImageName;
import com.auto1.pantera.docker.cooldown.ManifestCooldown;
import com.auto1.pantera.docker.misc.OfficialImageName;
import com.auto1.pantera.docker.http.DockerSlice;
import com.auto1.pantera.docker.http.PathPatterns;
import com.auto1.pantera.docker.http.TrimmedDocker;
import com.auto1.pantera.docker.proxy.ProxyDocker;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.publishdate.DbPublishDateRegistry;
import com.auto1.pantera.publishdate.PublishDateRegistries;
import com.auto1.pantera.http.auth.AuthScheme;
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
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.index.SyncArtifactIndexer;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.repo.RepoConfig;

import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.RequestTrace;

import java.util.List;
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
        EcsLogger.info("com.auto1.pantera.adapters.docker")
            .message("DockerProxy request")
            .eventCategory("web")
            .eventAction("proxy_request")
            .field("http.request.method", line.method().value())
            .field("url.path", line.uri().getPath())
            .field("log.source", "application")
            .log();
        return this.delegate.response(line, headers, body)
            .whenComplete((resp, err) -> {
                final long duration = System.currentTimeMillis() - start;
                if (err != null) {
                    EcsLogger.error("com.auto1.pantera.adapters.docker")
                        .message("DockerProxy error")
                        .eventCategory("web")
                        .eventAction("proxy_request")
                        .eventOutcome("failure")
                        .field("url.path", line.uri().getPath())
                        .duration(duration)
                        .error(err)
                        .field("log.source", "application")
                        .log();
                } else {
                    EcsLogger.info("com.auto1.pantera.adapters.docker")
                        .message("DockerProxy response")
                        .eventCategory("web")
                        .eventAction("proxy_request")
                        .eventOutcome(resp.status().success() ? "success" : "failure")
                        .field("url.path", line.uri().getPath())
                        .field("http.response.status_code", resp.status().code())
                        .duration(duration)
                        .field("log.source", "application")
                        .log();
                }
            });
    }

    /**
     * Builds the read-through {@link Docker} a {@code docker-proxy} repo
     * serves (remotes unioned via {@link MultiReadDocker}, plus a local
     * cache tier via {@link ReadWriteDocker} when the repo has storage
     * configured) — without the HTTP-layer wrapping ({@link DockerSlice},
     * cooldown, routing, {@link TrimmedDocker} path-prefix stripping) that
     * {@link #createProxy} adds on top for a standalone repo.
     *
     * <p>Exists so a {@code docker-group} member of type {@code
     * docker-proxy} can be composed directly as a {@link Docker} object
     * (see {@code RepositorySlices} docker-group wiring) — the group
     * aggregates member {@link Docker}s in-process via {@link
     * MultiReadDocker}, mirroring exactly how {@code docker}/{@code
     * docker-proxy} construct their own {@link Docker}. No {@link
     * TrimmedDocker} wrap here: that strips a shared-port routing prefix
     * from the repository name, which is a standalone-repo HTTP concern —
     * the group already strips its own prefix once at the outer layer, so
     * member {@code repo(name)} calls receive a plain image name. Also uses
     * a throwaway {@link DockerProxyCooldownInspector} rather than sharing
     * one with a {@link DockerProxyCooldownSlice}: cooldown is not
     * re-applied at the group level (same convention as {@code
     * MavenGroupSlice} — CLAUDE.md), so nothing outside this method needs
     * to observe the inspector's state.
     *
     * @param client HTTP client for the repo's configured remotes.
     * @param cfg Repository configuration.
     * @param events Artifact metadata events queue.
     * @return Read-through {@link Docker} for the repo.
     */
    public static Docker buildDocker(
        final ClientSlices client,
        final RepoConfig cfg,
        final Optional<Queue<ArtifactEvent>> events
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
        return cfg.storageOpt()
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
        final OfficialImageName official = new OfficialImageName(
            cfg.remotes().stream().map(RemoteConfig::uri).toList()
        );
        final DockerProxyCooldownInspector inspector = new DockerProxyCooldownInspector(official);
        final var registry = PublishDateRegistries.instance();
        if (registry instanceof DbPublishDateRegistry dbRegistry) {
            inspector.setReleaseDateCallback(new DockerReleaseDates(dbRegistry, cfg.type()));
        }
        cfg.storageOpt().ifPresent(
            storage -> CooldownLinkedVersions.instance().register(
                cfg.name(),
                new DockerLinkedDigests(
                    new AstoDocker(cfg.name(), new SubStorage(RegistryRoot.V2, storage)),
                    official
                )
            )
        );
        final List<Docker> remotes = cfg.remotes().stream()
            .map(r -> proxy(client, cfg, events, r, inspector))
            .toList();
        final AuthScheme scheme = new CombinedAuthScheme(auth, tokens);
        final CooldownImageName names = new CooldownImageName(cfg.name(), official);
        final Slice serving = serving(
            cfg, policy, scheme, events, cooldown, inspector, names, view(cfg, remotes)
        );
        // A Troubleshoot replay (RequestTrace) asks what a client would
        // get and must leave the repository as it found it. It is answered
        // by a second view of the same repository: the same cache storage
        // and the same upstream clients, read in the same order, but
        // nothing is stored, no artifact event is queued and the release
        // dates cooldown is evaluated with stay on an inspector of that one
        // request. The registry API carries no request headers down to the
        // cache layer, so the view is chosen here, where they are known.
        final Docker unstored = view(
            cfg,
            remotes.stream()
                .map(remote -> remote instanceof CacheDocker cached ? cached.readOnly() : remote)
                .toList()
        );
        final ManifestCooldown check = new ManifestCooldown(
            cooldown, inspector, cfg.type(), cfg.name()
        );
        Slice slice = (line, headers, body) -> {
            final boolean manifest =
                PathPatterns.MANIFESTS.matcher(line.uri().getPath()).matches();
            final CompletableFuture<Response> res;
            if (RequestTrace.instance().traced(headers)) {
                res = serving(
                    cfg, policy, scheme, Optional.empty(), cooldown,
                    inspector.overlay(), names, unstored
                ).response(line, headers, body);
            } else if (manifest && line.method() == RqMethod.GET) {
                // Cooldown decides on a manifest after the cache layer read
                // it from the upstream. The request is read through a view
                // that holds the copy of that manifest into the cache (and
                // its event for the search index) until the answer is
                // known: a version cooldown refuses is neither stored nor
                // indexed. Same storage, upstream clients and copies in
                // flight as the repository's own view.
                final ManifestCopies copies = new ManifestCopies();
                res = serving(
                    cfg, policy, scheme, events, cooldown, inspector, names,
                    deciding(cfg, remotes, copies)
                ).response(line, headers, body).whenComplete(
                    (resp, err) -> {
                        if (err == null && resp.status().success()) {
                            copies.commit();
                        } else {
                            copies.discard();
                        }
                    }
                );
            } else if (manifest && line.method() == RqMethod.HEAD) {
                // The answer to a HEAD is never refused (containerd resolves
                // a tag with it, then GETs the digest, which cooldown
                // decides on). The copy of the manifest it read is stored,
                // and indexed, only when cooldown allows the version,
                // decided as for a GET once the copy has read the release
                // date.
                res = serving(
                    cfg, policy, scheme, events, cooldown, inspector, names,
                    deciding(
                        cfg, remotes,
                        new ManifestCopies(DockerProxy.verdict(check, names, headers))
                    )
                ).response(line, headers, body);
            } else {
                res = serving.response(line, headers, body);
            }
            return res;
        };
        if (cfg.port().isEmpty()) {
            slice = new DockerRoutingSlice.Reverted(slice);
        }
        return slice;
    }

    /**
     * The repository for one client's request of a manifest: its manifest
     * copies are made as {@code copies} decide.
     *
     * @param cfg Repository configuration
     * @param remotes One Docker per remote
     * @param copies Copies of the manifests the request reads
     * @return Docker addressed by the repository-prefixed image name
     */
    private static Docker deciding(
        final RepoConfig cfg, final List<Docker> remotes, final ManifestCopies copies
    ) {
        return view(
            cfg,
            remotes.stream()
                .map(remote -> remote instanceof CacheDocker cached ? cached.deciding(copies) : remote)
                .toList()
        );
    }

    /**
     * Cooldown verdict on the copy of a manifest a HEAD read: its tag and
     * the digest the tag resolves to, or its digest, as a GET is decided.
     *
     * @param check Cooldown decision on a manifest
     * @param names Canonical cooldown artifact naming
     * @param headers Request headers
     * @return Verdict
     */
    private static ManifestCopies.Verdict verdict(
        final ManifestCooldown check, final CooldownImageName names, final Headers headers
    ) {
        final String user = new Login(headers).getValue();
        return (image, ref, manifest) -> check.refusal(
            names.of(image), ref.digest(), Optional.of(manifest.digest().string()), user
        ).thenApply(Optional::isEmpty);
    }

    /**
     * The repository as the registry API reads it: its cache storage first,
     * then its remotes.
     *
     * @param cfg Repository configuration
     * @param remotes One Docker per remote
     * @return Docker addressed by the repository-prefixed image name
     */
    private static Docker view(final RepoConfig cfg, final List<Docker> remotes) {
        final Docker proxies = new MultiReadDocker(remotes);
        final Docker docker = cfg.storageOpt()
            .<Docker>map(
                storage -> {
                    final AstoDocker local = new AstoDocker(
                        cfg.name(),
                        new SubStorage(RegistryRoot.V2, storage)
                    );
                    // Read-only for clients: uploads and manifest PUTs
                    // answer 405 UNSUPPORTED (MultiReadDocker refuses
                    // writes). Only the CacheDocker writers inside
                    // `proxies` populate this storage. A ReadWriteDocker
                    // here let any user with push overwrite a cached
                    // upstream tag for every puller (B35).
                    return new MultiReadDocker(local, proxies);
                }
            )
            .orElse(proxies);
        return new TrimmedDocker(docker, cfg.name());
    }

    /**
     * The registry API over one view of the repository, behind the cooldown
     * evaluation.
     *
     * @param cfg Repository configuration
     * @param policy Access policy
     * @param scheme Authentication scheme
     * @param events Artifact events queue
     * @param cooldown Cooldown service
     * @param inspector Cooldown inspector the release dates are recorded on
     * @param names Canonical cooldown artifact naming
     * @param docker View of the repository
     * @return Slice
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private static Slice serving(
        final RepoConfig cfg,
        final Policy<?> policy,
        final AuthScheme scheme,
        final Optional<Queue<ArtifactEvent>> events,
        final CooldownService cooldown,
        final DockerProxyCooldownInspector inspector,
        final CooldownImageName names,
        final Docker docker
    ) {
        // writable=false: push routes answer 405 UNSUPPORTED before any
        // repository lookup, so a refused push never fetches, caches or
        // publishes the upstream manifest, and upload bodies are drained.
        return new DockerProxyCooldownSlice(
            new DockerSlice(docker, policy, scheme, events, SyncArtifactIndexer.NOOP, false),
            cfg.name(),
            cfg.type(),
            cooldown,
            inspector,
            docker,
            names
        );
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
