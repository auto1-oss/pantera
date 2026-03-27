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
package com.auto1.pantera;

import com.auto1.pantera.adapters.docker.DockerProxy;
import com.auto1.pantera.adapters.file.FileProxy;
import com.auto1.pantera.adapters.go.GoProxy;

import com.auto1.pantera.adapters.maven.MavenProxy;
import com.auto1.pantera.adapters.php.ComposerGroupSlice;
import com.auto1.pantera.adapters.php.ComposerProxy;
import com.auto1.pantera.adapters.pypi.PypiProxy;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.SubStorage;
import com.auto1.pantera.auth.LoggingAuth;
import com.auto1.pantera.composer.AstoRepository;
import com.auto1.pantera.composer.http.PhpComposer;
import com.auto1.pantera.conan.ItemTokenizer;
import com.auto1.pantera.conan.http.ConanSlice;
import com.auto1.pantera.conda.http.CondaSlice;
import com.auto1.pantera.debian.Config;
import com.auto1.pantera.debian.http.DebianSlice;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.asto.AstoDocker;
import com.auto1.pantera.docker.asto.RegistryRoot;
import com.auto1.pantera.docker.http.DockerSlice;
import com.auto1.pantera.docker.http.TrimmedDocker;
import com.auto1.pantera.cooldown.CooldownService;
import com.auto1.pantera.cooldown.CooldownSupport;
import com.auto1.pantera.files.FilesSlice;
import com.auto1.pantera.gem.http.GemSlice;

import com.auto1.pantera.helm.http.HelmSlice;
import com.auto1.pantera.hex.http.HexSlice;
import com.auto1.pantera.http.ContentLengthRestriction;
import com.auto1.pantera.http.DockerRoutingSlice;
import com.auto1.pantera.http.GoSlice;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.TimeoutSlice;
import com.auto1.pantera.group.GroupSlice;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.BasicAuthScheme;
import com.auto1.pantera.http.auth.CombinedAuthScheme;
import com.auto1.pantera.http.auth.CombinedAuthzSliceWrap;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.auth.Tokens;
import com.auto1.pantera.http.client.HttpClientSettings;
import com.auto1.pantera.http.client.ProxySettings;
import com.auto1.pantera.http.client.jetty.JettyClientSlices;
import com.auto1.pantera.http.filter.FilterSlice;
import com.auto1.pantera.http.filter.Filters;
import com.auto1.pantera.http.slice.PathPrefixStripSlice;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.http.slice.TrimPathSlice;
import com.auto1.pantera.maven.http.MavenSlice;
import com.auto1.pantera.npm.http.NpmSlice;
import com.auto1.pantera.npm.proxy.NpmProxy;
import com.auto1.pantera.npm.proxy.http.NpmProxySlice;
import com.auto1.pantera.nuget.http.NuGet;
import com.auto1.pantera.pypi.http.PySlice;
import com.auto1.pantera.rpm.http.RpmSlice;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.scheduling.MetadataEventQueues;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.Settings;
import com.auto1.pantera.settings.repo.RepoConfig;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.settings.repo.Repositories;
import com.google.common.base.Strings;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.vertx.core.Vertx;
import org.eclipse.jetty.client.AbstractConnectionPool;
import org.eclipse.jetty.client.Destination;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

public class RepositorySlices {

    /**
     * Thread counter for the resolve executor pool.
     */
    private static final AtomicInteger RESOLVE_COUNTER = new AtomicInteger(0);

    /**
     * Dedicated executor for blocking slice resolution operations (e.g. Jetty client start).
     * Prevents blocking the Vert.x event loop when proxy repositories are initialized.
     */
    private static final ExecutorService RESOLVE_EXECUTOR =
        Executors.newFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors()),
            r -> {
                final Thread t = new Thread(
                    r, "slice-resolve-" + RESOLVE_COUNTER.incrementAndGet()
                );
                t.setDaemon(true);
                return t;
            }
        );

    /**
     * Pattern to trim path before passing it to adapters' slice.
     */
    private static final Pattern PATTERN = Pattern.compile("/(?:[^/.]+)(/.*)?");

    /**
     * Pantera settings.
     */
    private final Settings settings;

    private final Repositories repos;

    /**
     * Tokens: authentication and generation.
     */
    private final Tokens tokens;

    /**
     * Slice's cache.
     */
    private final LoadingCache<SliceKey, SliceValue> slices;

    /**
     * Cooldown service.
     */
    private final CooldownService cooldown;

    /**
     * Cooldown metadata filtering service.
     */
    private final com.auto1.pantera.cooldown.metadata.CooldownMetadataService cooldownMetadata;

    /**
     * Shared Jetty HTTP clients keyed by settings signature.
     */
    private final SharedJettyClients sharedClients;

    /**
     * @param settings Pantera settings
     * @param repos Repositories
     * @param tokens Tokens: authentication and generation
     */
    public RepositorySlices(
        final Settings settings,
        final Repositories repos,
        final Tokens tokens
    ) {
        this.settings = settings;
        this.repos = repos;
        this.tokens = tokens;
        this.cooldown = CooldownSupport.create(settings);
        this.cooldownMetadata = CooldownSupport.createMetadataService(this.cooldown, settings);
        this.sharedClients = new SharedJettyClients();
        this.slices = CacheBuilder.newBuilder()
            .maximumSize(500)
            .expireAfterAccess(30, java.util.concurrent.TimeUnit.MINUTES)
            .removalListener(
                (RemovalListener<SliceKey, SliceValue>) notification -> notification.getValue()
                    .client()
                    .ifPresent(SharedJettyClients.Lease::close)
            )
            .build(
                new CacheLoader<>() {
                    @Override
                    public SliceValue load(final SliceKey key) {
                        // Should not normally be used as we avoid caching NOT_FOUND entries
                        return resolve(key.name(), key.port(), 0).orElseGet(
                            () -> new SliceValue(
                                new SliceSimple(
                                    () -> ResponseBuilder.notFound()
                                        .textBody(
                                            String.format(
                                                "Repository '%s' not found",
                                                key.name().string()
                                            )
                                        )
                                        .build()
                                ),
                                Optional.empty()
                            )
                        );
                    }
                }
            );
    }

    /**
     * Resolve slice by name and port (top-level call with depth=0).
     * @param name Repository name
     * @param port Server port
     * @return Resolved slice
     */
    public Slice slice(final Key name, final int port) {
        return slice(name, port, 0);
    }

    /**
     * Resolve slice by name, port, and nesting depth.
     * @param name Repository name
     * @param port Server port
     * @param depth Nesting depth (0 for top-level, incremented in nested groups)
     * @return Resolved slice
     */
    public Slice slice(final Key name, final int port, final int depth) {
        final SliceKey skey = new SliceKey(name, port);
        final SliceValue cached = this.slices.getIfPresent(skey);
        if (cached != null) {
            EcsLogger.debug("com.auto1.pantera.settings")
                .message("Repository slice resolved from cache")
                .eventCategory("repository")
                .eventAction("slice_resolve")
                .eventOutcome("success")
                .field("repository.name", name.string())
                .field("url.port", port)
                .log();
            return cached.slice();
        }
        final Optional<SliceValue> resolved = resolve(name, port, depth);
        if (resolved.isPresent()) {
            this.slices.put(skey, resolved.get());
            EcsLogger.debug("com.auto1.pantera.settings")
                .message("Repository slice resolved and cached from config")
                .eventCategory("repository")
                .eventAction("slice_resolve")
                .eventOutcome("success")
                .field("repository.name", name.string())
                .field("url.port", port)
                .log();
            return resolved.get().slice();
        }
        // Not found is NOT cached to allow dynamic repo addition without restart
        EcsLogger.warn("com.auto1.pantera.settings")
            .message("Repository not found in configuration")
            .eventCategory("repository")
            .eventAction("slice_resolve")
            .eventOutcome("failure")
            .field("repository.name", name.string())
            .field("url.port", port)
            .log();
        return new SliceSimple(
            () -> ResponseBuilder.notFound()
                .textBody(String.format("Repository '%s' not found", name.string()))
                .build()
        );
    }

    /**
     * Resolve {@link Slice} by provided configuration.
     *
     * @param name Repository name
     * @param port Repository port
     * @param depth Nesting depth for group repositories
     * @return Slice for repo
     */
    private Optional<SliceValue> resolve(final Key name, final int port, final int depth) {
        Optional<RepoConfig> opt = repos.config(name.string());
        if (opt.isPresent()) {
            final RepoConfig cfg = opt.get();
            if (cfg.port().isEmpty() || cfg.port().getAsInt() == port) {
                return Optional.of(sliceFromConfig(cfg, port, depth));
            }
        }
        return Optional.empty();
    }

    /**
     * Invalidate all cached slices for repository name across all ports.
     * @param name Repository name
     */
    public void invalidateRepo(final String name) {
        this.slices.asMap().keySet().stream()
            .filter(k -> k.name().string().equals(name))
            .forEach(this.slices::invalidate);
    }

    public void enableJettyMetrics(final MeterRegistry registry) {
        this.sharedClients.enableMetrics(registry);
    }

    /**
     * Access underlying repositories registry.
     *
     * @return Repositories instance
     */
    public Repositories repositories() {
        return this.repos;
    }

    private Optional<Queue<ArtifactEvent>> artifactEvents() {
        return this.settings.artifactMetadata()
            .map(MetadataEventQueues::eventQueue);
    }

    private SliceValue sliceFromConfig(final RepoConfig cfg, final int port, final int depth) {
        Slice slice;
        SharedJettyClients.Lease clientLease = null;
        JettyClientSlices clientSlices = null;
        try {
            switch (cfg.type()) {
            case "file":
                slice = browsableTrimPathSlice(
                    new FilesSlice(
                        cfg.storage(),
                        securityPolicy(),
                        authentication(),
                        tokens.auth(),
                        cfg.name(),
                        artifactEvents()
                    ),
                    cfg.storage()
                );
                break;
            case "file-proxy":
                clientLease = jettyClientSlices(cfg);
                clientSlices = clientLease.client();
                final Slice fileProxySlice = new TimeoutSlice(
                    new FileProxy(clientSlices, cfg, artifactEvents(), this.cooldown),
                    settings.httpClientSettings().proxyTimeout()
                );
                // Browsing disabled for proxy repos - files are fetched on-demand from upstream
                slice = trimPathSlice(fileProxySlice);
                break;
            case "npm":
                slice = browsableTrimPathSlice(
                    new NpmSlice(
                        cfg.url(), cfg.storage(), securityPolicy(), authentication(), tokens.auth(), tokens, cfg.name(), artifactEvents(), true
                    ),
                    cfg.storage()
                );
                break;
            case "gem":
                slice = browsableTrimPathSlice(
                    new GemSlice(
                        cfg.storage(),
                        securityPolicy(),
                        authentication(),
                        tokens.auth(),
                        cfg.name(),
                        artifactEvents()
                    ),
                    cfg.storage()
                );
                break;
            case "helm":
                slice = browsableTrimPathSlice(
                    new HelmSlice(
                        cfg.storage(), cfg.url().toString(), securityPolicy(), authentication(), tokens.auth(), cfg.name(), artifactEvents()
                    ),
                    cfg.storage()
                );
                break;
            case "rpm":
                slice = browsableTrimPathSlice(
                    new RpmSlice(cfg.storage(), securityPolicy(), authentication(),
                        tokens.auth(), new com.auto1.pantera.rpm.RepoConfig.FromYaml(cfg.settings(), cfg.name()), Optional.empty()),
                    cfg.storage()
                );
                break;
            case "php":
                // Extract base URL from config, handling trailing slashes consistently
                // The URL should be the full path to the repository for provider URLs to work
                String baseUrl = cfg.settings()
                    .flatMap(yaml -> Optional.ofNullable(yaml.string("url")))
                    .orElseGet(() -> cfg.url().toString());
                
                // Normalize: remove all trailing slashes
                baseUrl = baseUrl.replaceAll("/+$", "");
                
                // Ensure URL ends with the repository name for correct routing
                // Provider URLs will be: {baseUrl}/p2/%package%.json
                String normalizedRepo = cfg.name().replaceAll("^/+", "").replaceAll("/+$", "");
                if (!baseUrl.endsWith("/" + normalizedRepo)) {
                    baseUrl = baseUrl + "/" + normalizedRepo;
                }
                
                slice = browsableTrimPathSlice(
                    new PathPrefixStripSlice(
                        new PhpComposer(
                            new AstoRepository(
                                cfg.storage(),
                                Optional.of(baseUrl),
                                Optional.of(cfg.name())
                            ),
                            securityPolicy(),
                            authentication(),
                            tokens.auth(),
                            cfg.name(),
                            artifactEvents()
                        ),
                        "direct-dists"
                    ),
                    cfg.storage()
                );
                break;
            case "php-proxy":
                clientLease = jettyClientSlices(cfg);
                clientSlices = clientLease.client();
                slice = trimPathSlice(
                    new PathPrefixStripSlice(
                        new TimeoutSlice(
                            new ComposerProxy(
                                clientSlices,
                                cfg,
                                settings.artifactMetadata().flatMap(queues -> queues.proxyEventQueues(cfg)),
                                this.cooldown
                            ),
                            settings.httpClientSettings().proxyTimeout()
                        ),
                        "direct-dists"
                    )
                );
                break;
            case "nuget":
                slice = browsableTrimPathSlice(
                    new NuGet(
                        cfg.url(), new com.auto1.pantera.nuget.AstoRepository(cfg.storage()),
                        securityPolicy(), authentication(), tokens.auth(), cfg.name(), artifactEvents()
                    ),
                    cfg.storage()
                );
                break;
            case "gradle":
            case "maven":
                slice = browsableTrimPathSlice(
                    new MavenSlice(cfg.storage(), securityPolicy(),
                        authentication(), tokens.auth(), cfg.name(), artifactEvents()),
                    cfg.storage()
                );
                break;
            case "gradle-proxy":
            case "maven-proxy":
                clientLease = jettyClientSlices(cfg);
                clientSlices = clientLease.client();
                final Slice mavenProxySlice = new CombinedAuthzSliceWrap(
                    new TimeoutSlice(
                        new MavenProxy(
                            clientSlices,
                            cfg,
                            settings.artifactMetadata().flatMap(queues -> queues.proxyEventQueues(cfg)),
                            this.cooldown
                        ),
                        settings.httpClientSettings().proxyTimeout()
                    ),
                    authentication(),
                    tokens.auth(),
                    new OperationControl(
                        securityPolicy(),
                        new AdapterBasicPermission(cfg.name(), Action.Standard.READ)
                    )
                );
                // Browsing disabled for proxy repos - files are fetched on-demand from upstream
                // Directory structure is not meaningful for proxies
                slice = trimPathSlice(mavenProxySlice);
                break;
            case "go":
                slice = browsableTrimPathSlice(
                    new GoSlice(
                        cfg.storage(),
                        securityPolicy(),
                        authentication(),
                        tokens.auth(),
                        cfg.name(),
                        artifactEvents()
                    ),
                    cfg.storage()
                );
                break;
            case "go-proxy":
                clientLease = jettyClientSlices(cfg);
                clientSlices = clientLease.client();
                slice = trimPathSlice(
                    new CombinedAuthzSliceWrap(
                        new TimeoutSlice(
                            new GoProxy(
                                clientSlices,
                                cfg,
                                settings.artifactMetadata().flatMap(queues -> queues.proxyEventQueues(cfg)),
                                this.cooldown
                            ),
                            settings.httpClientSettings().proxyTimeout()
                        ),
                        authentication(),
                        tokens.auth(),
                        new OperationControl(
                            securityPolicy(),
                            new AdapterBasicPermission(cfg.name(), Action.Standard.READ)
                        )
                    )
                );
                break;
            case "npm-proxy":
                clientLease = jettyClientSlices(cfg);
                clientSlices = clientLease.client();
                final Slice npmProxySlice = new TimeoutSlice(
                    new com.auto1.pantera.adapters.npm.NpmProxyAdapter(
                        clientSlices,
                        cfg,
                        settings.artifactMetadata().flatMap(queues -> queues.proxyEventQueues(cfg)),
                        this.cooldown,
                        this.cooldownMetadata
                    ),
                    settings.httpClientSettings().proxyTimeout()
                );
                // npm-proxy routing: audit anonymous (via SecurityAuditProxySlice), login blocked, downloads require JWT
                slice = trimPathSlice(
                    new com.auto1.pantera.http.rt.SliceRoute(
                    // Audit - anonymous, SecurityAuditProxySlice already strips headers
                    new com.auto1.pantera.http.rt.RtRulePath(
                        new com.auto1.pantera.http.rt.RtRule.All(
                            com.auto1.pantera.http.rt.MethodRule.POST,
                            new com.auto1.pantera.http.rt.RtRule.ByPath(".*/-/npm/v1/security/.*")
                        ),
                        npmProxySlice
                    ),
                    // Block login/adduser/whoami - proxy is read-only
                    // NOTE: Do NOT block generic /auth paths - they conflict with scoped packages
                    // like @verdaccio/auth. Standard NPM auth uses /-/user/ and /-/v1/login.
                    new com.auto1.pantera.http.rt.RtRulePath(
                        new com.auto1.pantera.http.rt.RtRule.Any(
                            new com.auto1.pantera.http.rt.RtRule.ByPath(".*/-/v1/login.*"),
                            new com.auto1.pantera.http.rt.RtRule.ByPath(".*/-/user/.*"),
                            new com.auto1.pantera.http.rt.RtRule.ByPath(".*/-/whoami.*")
                        ),
                        new com.auto1.pantera.http.slice.SliceSimple(
                            com.auto1.pantera.http.ResponseBuilder.forbidden()
                                .textBody("User management not supported on proxy. Use local npm repository.")
                                .build()
                        )
                    ),
                    // Downloads - require Keycloak JWT
                    new com.auto1.pantera.http.rt.RtRulePath(
                        com.auto1.pantera.http.rt.RtRule.FALLBACK,
                        new CombinedAuthzSliceWrap(
                            npmProxySlice,
                            authentication(),
                            tokens.auth(),
                            new OperationControl(
                                securityPolicy(),
                                new AdapterBasicPermission(cfg.name(), Action.Standard.READ)
                            )
                        )
                    )
                )
                );
                break;
            case "npm-group":
                final Slice npmGroupSlice = new GroupSlice(
                    this::slice, cfg.name(), cfg.members(), port, depth,
                    cfg.groupMemberTimeout().orElse(120L),
                    java.util.Collections.emptyList(),
                    Optional.of(this.settings.artifactIndex()),
                    proxyMembers(cfg.members()),
                    "npm-group"
                );
                // Create audit slice that aggregates results from ALL members
                // This is critical for vulnerability scanning - local repos return {},
                // but proxy repos return actual vulnerabilities from upstream
                // CRITICAL: Pass member NAMES so GroupAuditSlice can rewrite paths!
                final java.util.List<String> auditMemberNames = cfg.members();
                final java.util.List<Slice> auditMemberSlices = auditMemberNames.stream()
                    .map(name -> this.slice(new Key.From(name), port, 0))
                    .collect(java.util.stream.Collectors.toList());
                final Slice npmGroupAuditSlice = new com.auto1.pantera.npm.http.audit.GroupAuditSlice(
                    auditMemberNames, auditMemberSlices
                );
                // npm-group: audit anonymous, user management blocked, all other operations require auth
                slice = trimPathSlice(
                    new com.auto1.pantera.http.rt.SliceRoute(
                        // Audit - anonymous, uses GroupAuditSlice to aggregate from all members
                        new com.auto1.pantera.http.rt.RtRulePath(
                            new com.auto1.pantera.http.rt.RtRule.All(
                                com.auto1.pantera.http.rt.MethodRule.POST,
                                new com.auto1.pantera.http.rt.RtRule.ByPath(".*/-/npm/v1/security/.*")
                            ),
                            npmGroupAuditSlice
                        ),
                        // Block login/adduser/whoami - group is read-only
                        // NOTE: Do NOT block generic /auth paths - they conflict with scoped packages
                        // like @verdaccio/auth. Standard NPM auth uses /-/user/ and /-/v1/login.
                        new com.auto1.pantera.http.rt.RtRulePath(
                            new com.auto1.pantera.http.rt.RtRule.Any(
                                new com.auto1.pantera.http.rt.RtRule.ByPath(".*/-/v1/login.*"),
                                new com.auto1.pantera.http.rt.RtRule.ByPath(".*/-/user/.*"),
                                new com.auto1.pantera.http.rt.RtRule.ByPath(".*/-/whoami.*")
                            ),
                            new com.auto1.pantera.http.slice.SliceSimple(
                                com.auto1.pantera.http.ResponseBuilder.forbidden()
                                    .textBody("User management not supported on group. Use local npm repository.")
                                    .build()
                            )
                        ),
                        // All other operations - require JWT
                        new com.auto1.pantera.http.rt.RtRulePath(
                            com.auto1.pantera.http.rt.RtRule.FALLBACK,
                            new CombinedAuthzSliceWrap(
                                npmGroupSlice,
                                authentication(),
                                tokens.auth(),
                                new OperationControl(
                                    securityPolicy(),
                                    new AdapterBasicPermission(cfg.name(), Action.Standard.READ)
                                )
                            )
                        )
                    )
                );
                break;
            case "file-group":
            case "php-group":
                final GroupSlice composerDelegate = new GroupSlice(
                    this::slice, cfg.name(), cfg.members(), port, depth,
                    cfg.groupMemberTimeout().orElse(120L),
                    java.util.Collections.emptyList(),
                    Optional.of(this.settings.artifactIndex()),
                    proxyMembers(cfg.members()),
                    cfg.type()
                );
                slice = trimPathSlice(
                    new CombinedAuthzSliceWrap(
                        new ComposerGroupSlice(
                            composerDelegate,
                            this::slice, cfg.name(), cfg.members(), port,
                            this.settings.prefixes().prefixes().stream()
                                .findFirst().orElse("")
                        ),
                        authentication(),
                        tokens.auth(),
                        new OperationControl(
                            securityPolicy(),
                            new AdapterBasicPermission(cfg.name(), Action.Standard.READ)
                        )
                    )
                );
                break;
            case "maven-group":
                // Maven groups need special metadata merging
                final GroupSlice mavenDelegate = new GroupSlice(
                    this::slice, cfg.name(), cfg.members(), port, depth,
                    cfg.groupMemberTimeout().orElse(120L),
                    java.util.Collections.emptyList(),
                    Optional.of(this.settings.artifactIndex()),
                    proxyMembers(cfg.members()),
                    "maven-group"
                );
                slice = trimPathSlice(
                    new CombinedAuthzSliceWrap(
                        new com.auto1.pantera.group.MavenGroupSlice(
                            mavenDelegate,
                            cfg.name(),
                            cfg.members(),
                            this::slice,
                            port,
                            depth
                        ),
                        authentication(),
                        tokens.auth(),
                        new OperationControl(
                            securityPolicy(),
                            new AdapterBasicPermission(cfg.name(), Action.Standard.READ)
                        )
                    )
                );
                break;
            case "gem-group":
            case "go-group":
            case "gradle-group":
            case "pypi-group":
            case "docker-group":
                slice = trimPathSlice(
                    new CombinedAuthzSliceWrap(
                        new GroupSlice(
                            this::slice, cfg.name(), cfg.members(), port, depth,
                            cfg.groupMemberTimeout().orElse(120L),
                            java.util.Collections.emptyList(),
                            Optional.of(this.settings.artifactIndex()),
                            proxyMembers(cfg.members()),
                            cfg.type()
                        ),
                        authentication(),
                        tokens.auth(),
                        new OperationControl(
                            securityPolicy(),
                            new AdapterBasicPermission(cfg.name(), Action.Standard.READ)
                        )
                    )
                );
                break;
            case "pypi-proxy":
                clientLease = jettyClientSlices(cfg);
                clientSlices = clientLease.client();
                slice = trimPathSlice(
                    new PathPrefixStripSlice(
                        new CombinedAuthzSliceWrap(
                            new TimeoutSlice(
                                new PypiProxy(
                                    clientSlices,
                                    cfg,
                                    settings.artifactMetadata()
                                        .flatMap(queues -> queues.proxyEventQueues(cfg)),
                                    this.cooldown
                                ),
                                settings.httpClientSettings().proxyTimeout()
                            ),
                            authentication(),
                            tokens.auth(),
                            new OperationControl(
                                securityPolicy(),
                                new AdapterBasicPermission(cfg.name(), Action.Standard.READ)
                            )
                        ),
                        "simple"
                    )
                );
                break;
            case "docker":
                final Docker docker = new AstoDocker(
                    cfg.name(),
                    new SubStorage(RegistryRoot.V2, cfg.storage())
                );
                if (cfg.port().isPresent()) {
                    slice = new DockerSlice(docker, securityPolicy(),
                        new CombinedAuthScheme(authentication(), tokens.auth()), artifactEvents());
                } else {
                    slice = new DockerRoutingSlice.Reverted(
                        new DockerSlice(new TrimmedDocker(docker, cfg.name()),
                            securityPolicy(), new CombinedAuthScheme(authentication(), tokens.auth()),
                            artifactEvents())
                    );
                }
                break;
            case "docker-proxy":
                clientLease = jettyClientSlices(cfg);
                clientSlices = clientLease.client();
                slice = new TimeoutSlice(
                    new DockerProxy(
                        clientSlices,
                        cfg,
                        securityPolicy(),
                        authentication(),
                        tokens.auth(),
                        artifactEvents(),
                        this.cooldown
                    ),
                    settings.httpClientSettings().proxyTimeout()
                );
                break;
            case "deb":
                slice = trimPathSlice(
                    new DebianSlice(
                        cfg.storage(), securityPolicy(), authentication(),
                        new com.auto1.pantera.debian.Config.FromYaml(cfg.name(), cfg.settings(), settings.configStorage()),
                        artifactEvents()
                    )
                );
                break;
            case "conda":
                slice = new CondaSlice(
                    cfg.storage(), securityPolicy(), authentication(), tokens,
                    cfg.url().toString(), cfg.name(), artifactEvents()
                );
                break;
            case "conan":
                slice = new ConanSlice(
                    cfg.storage(), securityPolicy(), authentication(), tokens,
                    new ItemTokenizer(Vertx.vertx()), cfg.name()
                );
                break;
            case "hexpm":
                slice = trimPathSlice(
                    new HexSlice(cfg.storage(), securityPolicy(), authentication(),
                        artifactEvents(), cfg.name())
                );
                break;
            case "pypi":
                slice = trimPathSlice(
                    new PathPrefixStripSlice(
                        new com.auto1.pantera.pypi.http.PySlice(
                            cfg.storage(), securityPolicy(), authentication(),
                            cfg.name(), artifactEvents()
                        ),
                        "simple"
                    )
                );
                break;
            default:
                throw new IllegalStateException(
                    String.format("Unsupported repository type '%s", cfg.type())
                );
        }
        return new SliceValue(
            wrapIntoCommonSlices(slice, cfg),
            Optional.ofNullable(clientLease)
        );
        } catch (final Exception ex) {
            if (clientLease != null) {
                clientLease.close();
            }
            if (ex instanceof RuntimeException) {
                throw (RuntimeException) ex;
            }
            throw new IllegalStateException(
                String.format("Failed to construct adapter slice for '%s'", cfg.name()), ex
            );
        } catch (final Error ex) {
            if (clientLease != null) {
                clientLease.close();
            }
            throw ex;
        }
    }

    private Slice wrapIntoCommonSlices(
        final Slice origin,
        final RepoConfig cfg
    ) {
        Optional<Filters> opt = settings.caches()
            .filtersCache()
            .filters(cfg.name(), cfg.repoYaml());
        Slice filtered = opt.isPresent() ? new FilterSlice(origin, opt.get()) : origin;

        // Wrap with repository metrics to add repo_name and repo_type labels
        final Slice withMetrics = new com.auto1.pantera.http.slice.RepoMetricsSlice(
            filtered, cfg.name(), cfg.type()
        );

        return cfg.contentLengthMax()
            .<Slice>map(limit -> new ContentLengthRestriction(withMetrics, limit))
            .orElse(withMetrics);
    }

    private Authentication authentication() {
        return new LoggingAuth(settings.authz().authentication());
    }

    private Policy<?> securityPolicy() {
        return this.settings.authz().policy();
    }

    private SharedJettyClients.Lease jettyClientSlices(final RepoConfig cfg) {
        final HttpClientSettings effective = cfg.httpClientSettings()
            .orElseGet(settings::httpClientSettings);
        return this.sharedClients.acquire(effective);
    }

    private static Slice trimPathSlice(final Slice original) {
        return new TrimPathSlice(original, RepositorySlices.PATTERN);
    }

    /**
     * Wrap slice with BrowsableSlice inside TrimPathSlice.
     * BrowsableSlice must be inside TrimPathSlice so it sees the trimmed path
     * (e.g., "/" instead of "/reponame") when generating directory listings.
     *
     * @param origin Origin slice (the adapter slice)
     * @param storage Repository storage for directory listings
     * @return Slice chain: TrimPathSlice(BrowsableSlice(origin))
     */
    private static Slice browsableTrimPathSlice(final Slice origin, final com.auto1.pantera.asto.Storage storage) {
        return trimPathSlice(
            new com.auto1.pantera.http.slice.BrowsableSlice(origin, storage)
        );
    }

    /**
     * Determine which group members are proxy repositories.
     * A member is a proxy if its repo type ends with "-proxy".
     *
     * @param members Member repository names
     * @return Set of member names that are proxy repositories
     */
    private Set<String> proxyMembers(final List<String> members) {
        return members.stream()
            .filter(this::isProxyOrContainsProxy)
            .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Check if a member is a proxy repo or a group that contains proxy repos.
     * Nested groups that contain proxies must be treated as proxy-like because
     * their content is only indexed after being cached from upstream.
     * @param name Member repository name
     * @return True if proxy or group containing proxies
     */
    private boolean isProxyOrContainsProxy(final String name) {
        return this.repos.config(name)
            .map(c -> {
                final String type = c.type();
                if (type.endsWith("-proxy")) {
                    return true;
                }
                if (type.endsWith("-group")) {
                    return c.members().stream().anyMatch(this::isProxyOrContainsProxy);
                }
                return false;
            })
            .orElse(false);
    }


    /**
     * Slice's cache key.
     */
    record SliceKey(Key name, int port) {
    }

    /**
     * Slice's cache value.
     */
    record SliceValue(Slice slice, Optional<SharedJettyClients.Lease> client) {
    }

    /**
     * Stores and shares Jetty clients per unique HTTP client configuration.
     */
    private static final class SharedJettyClients {

        private final ConcurrentMap<HttpClientSettingsKey, SharedClient> clients = new ConcurrentHashMap<>();
        private final AtomicReference<MeterRegistry> metrics = new AtomicReference<>();

        Lease acquire(final HttpClientSettings settings) {
            final HttpClientSettingsKey key = HttpClientSettingsKey.from(settings);
            final SharedClient holder = this.clients.compute(
                key,
                (ignored, existing) -> {
                    if (existing == null) {
                        final SharedClient created = new SharedClient(key);
                        created.retain();
                        return created;
                    }
                    existing.retain();
                    return existing;
                }
            );
            final MeterRegistry registry = this.metrics.get();
            if (registry != null) {
                holder.registerMetrics(registry);
            }
            return new Lease(this, key, holder);
        }

        void enableMetrics(final MeterRegistry registry) {
            this.metrics.set(registry);
            this.clients.values().forEach(client -> client.registerMetrics(registry));
        }

        private void release(final HttpClientSettingsKey key, final SharedClient shared) {
            this.clients.computeIfPresent(
                key,
                (ignored, existing) -> {
                    if (existing != shared) {
                        return existing;
                    }
                    final int remaining = existing.release();
                    if (remaining == 0) {
                        existing.stop();
                        return null;
                    }
                    return existing;
                }
            );
        }

        static final class Lease implements AutoCloseable {
            private final SharedJettyClients owner;
            private final HttpClientSettingsKey key;
            private final SharedClient shared;
            private final AtomicBoolean closed = new AtomicBoolean(false);

            Lease(
                final SharedJettyClients owner,
                final HttpClientSettingsKey key,
                final SharedClient shared
            ) {
                this.owner = owner;
                this.key = key;
                this.shared = shared;
            }

            JettyClientSlices client() {
                return this.shared.client();
            }

            @Override
            public void close() {
                if (this.closed.compareAndSet(false, true)) {
                    this.owner.release(this.key, this.shared);
                }
            }
        }

        private static final class SharedClient {
            private final HttpClientSettingsKey key;
            private final JettyClientSlices client;
            private final CompletableFuture<Void> startFuture;
            private final AtomicInteger references = new AtomicInteger(0);
            private final AtomicBoolean metricsRegistered = new AtomicBoolean(false);

            SharedClient(final HttpClientSettingsKey key) {
                this.key = key;
                this.client = new JettyClientSlices(key.toSettings());
                // Start the Jetty client on the dedicated resolve executor to avoid
                // blocking the Vert.x event loop. The start() call can take 100ms+
                // due to SSL context initialization and socket setup.
                this.startFuture = CompletableFuture.runAsync(
                    this.client::start, RESOLVE_EXECUTOR
                );
            }

            void retain() {
                this.references.incrementAndGet();
            }

            int release() {
                final int remaining = this.references.updateAndGet(current -> Math.max(0, current - 1));
                if (remaining == 0 && this.references.get() == 0) {
                    EcsLogger.debug("com.auto1.pantera")
                        .message(String.format("Jetty client reference count reached zero for settings key '%s'", this.key.metricId()))
                        .eventCategory("http_client")
                        .eventAction("client_release")
                        .log();
                }
                return remaining;
            }

            JettyClientSlices client() {
                // Ensure the client has finished starting before returning it.
                // The actual start() runs on RESOLVE_EXECUTOR, not the calling thread.
                this.startFuture.join();
                return this.client;
            }

            void registerMetrics(final MeterRegistry registry) {
                if (!this.metricsRegistered.compareAndSet(false, true)) {
                    return;
                }
                Gauge.builder("jetty.connection_pool.active", this, SharedClient::activeConnections)
                    .strongReference(true)
                    .tag("settings", this.key.metricId())
                    .register(registry);
                Gauge.builder("jetty.connection_pool.idle", this, SharedClient::idleConnections)
                    .strongReference(true)
                    .tag("settings", this.key.metricId())
                    .register(registry);
                Gauge.builder("jetty.connection_pool.max", this, SharedClient::maxConnections)
                    .strongReference(true)
                    .tag("settings", this.key.metricId())
                    .register(registry);
                Gauge.builder("jetty.connection_pool.pending", this, SharedClient::pendingConnections)
                    .strongReference(true)
                    .tag("settings", this.key.metricId())
                    .register(registry);
            }

            private double activeConnections() {
                return this.connectionMetric(AbstractConnectionPool::getActiveConnectionCount);
            }

            private double idleConnections() {
                return this.connectionMetric(AbstractConnectionPool::getIdleConnectionCount);
            }

            private double maxConnections() {
                return this.connectionMetric(AbstractConnectionPool::getMaxConnectionCount);
            }

            private double pendingConnections() {
                return this.connectionMetric(AbstractConnectionPool::getPendingConnectionCount);
            }

            private double connectionMetric(final ToIntFunction<AbstractConnectionPool> extractor) {
                return this.client.httpClient().getDestinations().stream()
                    .map(Destination::getConnectionPool)
                    .filter(AbstractConnectionPool.class::isInstance)
                    .map(AbstractConnectionPool.class::cast)
                    .mapToInt(extractor)
                    .sum();
            }

            void stop() {
                // Wait for start to complete before stopping to avoid race conditions.
                this.startFuture.join();
                this.client.stop();
            }
        }
    }

    /**
     * Signature of HTTP client settings used as a cache key.
     */
    private static final class HttpClientSettingsKey {

        private final boolean trustAll;
        private final String jksPath;
        private final String jksPwd;
        private final boolean followRedirects;
        private final boolean http3;
        private final long connectTimeout;
        private final long idleTimeout;
        private final long proxyTimeout;
        private final long connectionAcquireTimeout;
        private final int maxConnectionsPerDestination;
        private final int maxRequestsQueuedPerDestination;
        private final List<ProxySettingsKey> proxies;

        private HttpClientSettingsKey(
            final boolean trustAll,
            final String jksPath,
            final String jksPwd,
            final boolean followRedirects,
            final boolean http3,
            final long connectTimeout,
            final long idleTimeout,
            final long proxyTimeout,
            final long connectionAcquireTimeout,
            final int maxConnectionsPerDestination,
            final int maxRequestsQueuedPerDestination,
            final List<ProxySettingsKey> proxies
        ) {
            this.trustAll = trustAll;
            this.jksPath = jksPath;
            this.jksPwd = jksPwd;
            this.followRedirects = followRedirects;
            this.http3 = http3;
            this.connectTimeout = connectTimeout;
            this.idleTimeout = idleTimeout;
            this.proxyTimeout = proxyTimeout;
            this.connectionAcquireTimeout = connectionAcquireTimeout;
            this.maxConnectionsPerDestination = maxConnectionsPerDestination;
            this.maxRequestsQueuedPerDestination = maxRequestsQueuedPerDestination;
            this.proxies = proxies;
        }

        static HttpClientSettingsKey from(final HttpClientSettings settings) {
            return new HttpClientSettingsKey(
                settings.trustAll(),
                settings.jksPath(),
                settings.jksPwd(),
                settings.followRedirects(),
                settings.http3(),
                settings.connectTimeout(),
                settings.idleTimeout(),
                settings.proxyTimeout(),
                settings.connectionAcquireTimeout(),
                settings.maxConnectionsPerDestination(),
                settings.maxRequestsQueuedPerDestination(),
                settings.proxies()
                    .stream()
                    .map(ProxySettingsKey::from)
                    .collect(Collectors.toUnmodifiableList())
            );
        }

        HttpClientSettings toSettings() {
            final HttpClientSettings copy = new HttpClientSettings()
                .setTrustAll(this.trustAll)
                .setFollowRedirects(this.followRedirects)
                .setHttp3(this.http3)
                .setConnectTimeout(this.connectTimeout)
                .setIdleTimeout(this.idleTimeout)
                .setProxyTimeout(this.proxyTimeout)
                .setConnectionAcquireTimeout(this.connectionAcquireTimeout)
                .setMaxConnectionsPerDestination(this.maxConnectionsPerDestination)
                .setMaxRequestsQueuedPerDestination(this.maxRequestsQueuedPerDestination);
            if (this.jksPath != null) {
                copy.setJksPath(this.jksPath);
            }
            if (this.jksPwd != null) {
                copy.setJksPwd(this.jksPwd);
            }
            final Set<String> seen = new HashSet<>();
            copy.proxies().forEach(proxy -> seen.add(proxy.uri().toString()));
            for (final ProxySettingsKey proxy : this.proxies) {
                if (seen.add(proxy.uri())) {
                    copy.addProxy(proxy.toProxySettings());
                }
            }
            return copy;
        }

        String metricId() {
            return Integer.toHexString(this.hashCode());
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof HttpClientSettingsKey other)) {
                return false;
            }
            return this.trustAll == other.trustAll
                && this.followRedirects == other.followRedirects
                && this.http3 == other.http3
                && this.connectTimeout == other.connectTimeout
                && this.idleTimeout == other.idleTimeout
                && this.proxyTimeout == other.proxyTimeout
                && this.connectionAcquireTimeout == other.connectionAcquireTimeout
                && this.maxConnectionsPerDestination == other.maxConnectionsPerDestination
                && this.maxRequestsQueuedPerDestination == other.maxRequestsQueuedPerDestination
                && Objects.equals(this.jksPath, other.jksPath)
                && Objects.equals(this.jksPwd, other.jksPwd)
                && Objects.equals(this.proxies, other.proxies);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                this.trustAll,
                this.jksPath,
                this.jksPwd,
                this.followRedirects,
                this.http3,
                this.connectTimeout,
                this.idleTimeout,
                this.proxyTimeout,
                this.connectionAcquireTimeout,
                this.maxConnectionsPerDestination,
                this.maxRequestsQueuedPerDestination,
                this.proxies
            );
        }
    }

    private record ProxySettingsKey(
        String uri,
        String realm,
        String user,
        String password
    ) {

        static ProxySettingsKey from(final ProxySettings proxy) {
            return new ProxySettingsKey(
                proxy.uri().toString(),
                proxy.basicRealm(),
                proxy.basicUser(),
                proxy.basicPwd()
            );
        }

        ProxySettings toProxySettings() {
            final ProxySettings proxy = new ProxySettings(URI.create(this.uri));
            if (!Strings.isNullOrEmpty(this.realm)) {
                proxy.setBasicRealm(this.realm);
                proxy.setBasicUser(this.user);
                proxy.setBasicPwd(this.password);
            }
            return proxy;
        }

        @Override
        public String toString() {
            return String.format("ProxySettingsKey{uri='%s'}", this.uri);
        }
    }
}
