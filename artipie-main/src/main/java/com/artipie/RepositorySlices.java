/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie;

import com.artipie.adapters.docker.DockerProxy;
import com.artipie.adapters.file.FileProxy;
import com.artipie.adapters.go.GoProxy;
import com.artipie.adapters.gradle.GradleProxy;
import com.artipie.adapters.maven.MavenProxy;
import com.artipie.adapters.php.ComposerGroupSlice;
import com.artipie.adapters.php.ComposerProxy;
import com.artipie.adapters.pypi.PypiProxy;
import com.artipie.asto.Key;
import com.artipie.asto.SubStorage;
import com.artipie.auth.LoggingAuth;
import com.artipie.composer.AstoRepository;
import com.artipie.composer.http.PhpComposer;
import com.artipie.conan.ItemTokenizer;
import com.artipie.conan.http.ConanSlice;
import com.artipie.conda.http.CondaSlice;
import com.artipie.debian.Config;
import com.artipie.debian.http.DebianSlice;
import com.artipie.docker.Docker;
import com.artipie.docker.asto.AstoDocker;
import com.artipie.docker.asto.RegistryRoot;
import com.artipie.docker.http.DockerSlice;
import com.artipie.docker.http.TrimmedDocker;
import com.artipie.cooldown.CooldownService;
import com.artipie.cooldown.CooldownSupport;
import com.artipie.files.FilesSlice;
import com.artipie.gem.http.GemSlice;
import com.artipie.gradle.http.GradleSlice;
import com.artipie.helm.http.HelmSlice;
import com.artipie.hex.http.HexSlice;
import com.artipie.http.ContentLengthRestriction;
import com.artipie.http.DockerRoutingSlice;
import com.artipie.http.GoSlice;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.TimeoutSlice;
import com.artipie.group.GroupSlice;
import com.artipie.http.auth.Authentication;
import com.artipie.http.auth.BasicAuthScheme;
import com.artipie.http.auth.CombinedAuthScheme;
import com.artipie.http.auth.CombinedAuthzSliceWrap;
import com.artipie.http.auth.TokenAuthentication;
import com.artipie.http.auth.OperationControl;
import com.artipie.http.auth.Tokens;
import com.artipie.http.client.HttpClientSettings;
import com.artipie.http.client.ProxySettings;
import com.artipie.http.client.jetty.JettyClientSlices;
import com.artipie.http.filter.FilterSlice;
import com.artipie.http.filter.Filters;
import com.artipie.http.slice.PathPrefixStripSlice;
import com.artipie.http.slice.SliceSimple;
import com.artipie.http.slice.TrimPathSlice;
import com.artipie.maven.http.MavenSlice;
import com.artipie.npm.http.NpmSlice;
import com.artipie.npm.proxy.NpmProxy;
import com.artipie.npm.proxy.http.NpmProxySlice;
import com.artipie.nuget.http.NuGet;
import com.artipie.pypi.http.PySlice;
import com.artipie.rpm.http.RpmSlice;
import com.artipie.scheduling.ArtifactEvent;
import com.artipie.scheduling.MetadataEventQueues;
import com.artipie.security.policy.Policy;
import com.artipie.settings.Settings;
import com.artipie.settings.repo.RepoConfig;
import com.artipie.security.perms.Action;
import com.artipie.security.perms.AdapterBasicPermission;
import com.artipie.settings.repo.Repositories;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

public class RepositorySlices {

    /**
     * Pattern to trim path before passing it to adapters' slice.
     */
    private static final Pattern PATTERN = Pattern.compile("/(?:[^/.]+)(/.*)?");

    /**
     * Artipie settings.
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
     * Shared Jetty HTTP clients keyed by settings signature.
     */
    private final SharedJettyClients sharedClients;

    /**
     * @param settings Artipie settings
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
        this.sharedClients = new SharedJettyClients();
        this.slices = CacheBuilder.newBuilder()
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
            return cached.slice();
        }
        final Optional<SliceValue> resolved = resolve(name, port, depth);
        if (resolved.isPresent()) {
            this.slices.put(skey, resolved.get());
            return resolved.get().slice();
        }
        // Not found is NOT cached to allow dynamic repo addition without restart
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
                // Use streaming browsing for fast directory listings
                slice = trimPathSlice(
                    new com.artipie.http.slice.BrowsableSlice(
                        new FilesSlice(
                            cfg.storage(),
                            securityPolicy(),
                            authentication(),
                            tokens.auth(),
                            cfg.name(),
                            artifactEvents()
                        ),
                        cfg.storage()
                    )
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
                slice = trimPathSlice(
                    new com.artipie.http.slice.BrowsableSlice(
                        new NpmSlice(
                            cfg.url(), cfg.storage(), securityPolicy(), authentication(), tokens.auth(), tokens, cfg.name(), artifactEvents(), true  // JWT-only, no npm tokens
                        ),
                        cfg.storage()
                    )
                );
                break;
            case "gem":
                slice = trimPathSlice(
                    new com.artipie.http.slice.BrowsableSlice(
                        new GemSlice(
                            cfg.storage(),
                            securityPolicy(),
                            authentication(),
                            tokens.auth(),
                            cfg.name(),
                            artifactEvents()
                        ),
                        cfg.storage()
                    )
                );
                break;
            case "helm":
                slice = trimPathSlice(
                    new com.artipie.http.slice.BrowsableSlice(
                        new HelmSlice(
                            cfg.storage(), cfg.url().toString(), securityPolicy(), authentication(), tokens.auth(), cfg.name(), artifactEvents()
                        ),
                        cfg.storage()
                    )
                );
                break;
            case "rpm":
                slice = trimPathSlice(
                    new com.artipie.http.slice.BrowsableSlice(
                        new RpmSlice(cfg.storage(), securityPolicy(), authentication(),
                            tokens.auth(), new com.artipie.rpm.RepoConfig.FromYaml(cfg.settings(), cfg.name()), Optional.empty()),
                        cfg.storage()
                    )
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
                
                slice = trimPathSlice(
                    new PathPrefixStripSlice(
                        new com.artipie.http.slice.BrowsableSlice(
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
                            cfg.storage()
                        ),
                        "direct-dists"
                    )
                );
                break;
            case "php-proxy":
                clientLease = jettyClientSlices(cfg);
                clientSlices = clientLease.client();
                slice = trimPathSlice(
                    new PathPrefixStripSlice(
                        new com.artipie.http.slice.BrowsableSlice(
                            new TimeoutSlice(
                                new ComposerProxy(
                                    clientSlices,
                                    cfg,
                                    settings.artifactMetadata().flatMap(queues -> queues.proxyEventQueues(cfg)),
                                    this.cooldown
                                ),
                                settings.httpClientSettings().proxyTimeout()
                            ),
                            cfg.storage()
                        ),
                        "direct-dists"
                    )
                );
                break;
            case "nuget":
                slice = trimPathSlice(
                    new com.artipie.http.slice.BrowsableSlice(
                        new NuGet(
                            cfg.url(), new com.artipie.nuget.AstoRepository(cfg.storage()),
                            securityPolicy(), authentication(), tokens.auth(), cfg.name(), artifactEvents()
                        ),
                        cfg.storage()
                    )
                );
                break;
            case "gradle":
                // Use streaming browsing for fast directory listings
                slice = trimPathSlice(
                    new com.artipie.http.slice.BrowsableSlice(
                        new GradleSlice(cfg.storage(), securityPolicy(),
                            authentication(), cfg.name(), artifactEvents()),
                        cfg.storage()
                    )
                );
                break;
            case "gradle-proxy":
                clientLease = jettyClientSlices(cfg);
                clientSlices = clientLease.client();
                final Slice gradleProxySlice = new CombinedAuthzSliceWrap(
                    new TimeoutSlice(
                        new GradleProxy(
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
                slice = trimPathSlice(gradleProxySlice);
                break;
            case "maven":
                // Use streaming browsing for fast directory listings
                slice = trimPathSlice(
                    new com.artipie.http.slice.BrowsableSlice(
                        new MavenSlice(cfg.storage(), securityPolicy(),
                            authentication(), tokens.auth(), cfg.name(), artifactEvents()),
                        cfg.storage()
                    )
                );
                break;
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
                // Use streaming browsing for fast directory listings
                slice = trimPathSlice(
                    new com.artipie.http.slice.BrowsableSlice(
                        new GoSlice(
                            cfg.storage(),
                            securityPolicy(),
                            authentication(),
                            tokens.auth(),
                            cfg.name(),
                            artifactEvents()
                        ),
                        cfg.storage()
                    )
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
                    new com.artipie.adapters.npm.NpmProxyAdapter(
                        clientSlices,
                        cfg,
                        settings.artifactMetadata().flatMap(queues -> queues.proxyEventQueues(cfg)),
                        this.cooldown
                    ),
                    settings.httpClientSettings().proxyTimeout()
                );
                // npm-proxy routing: audit anonymous (via SecurityAuditProxySlice), login blocked, downloads require JWT
                slice = trimPathSlice(
                    new com.artipie.http.rt.SliceRoute(
                    // Audit - anonymous, SecurityAuditProxySlice already strips headers
                    new com.artipie.http.rt.RtRulePath(
                        new com.artipie.http.rt.RtRule.All(
                            com.artipie.http.rt.MethodRule.POST,
                            new com.artipie.http.rt.RtRule.ByPath(".*/-/npm/v1/security/.*")
                        ),
                        npmProxySlice
                    ),
                    // Block login/adduser/whoami - proxy is read-only
                    // NOTE: Do NOT block generic /auth paths - they conflict with scoped packages
                    // like @verdaccio/auth. Standard NPM auth uses /-/user/ and /-/v1/login.
                    new com.artipie.http.rt.RtRulePath(
                        new com.artipie.http.rt.RtRule.Any(
                            new com.artipie.http.rt.RtRule.ByPath(".*/-/v1/login.*"),
                            new com.artipie.http.rt.RtRule.ByPath(".*/-/user/.*"),
                            new com.artipie.http.rt.RtRule.ByPath(".*/-/whoami.*")
                        ),
                        new com.artipie.http.slice.SliceSimple(
                            com.artipie.http.ResponseBuilder.forbidden()
                                .textBody("User management not supported on proxy. Use local npm repository.")
                                .build()
                        )
                    ),
                    // Downloads - require Keycloak JWT
                    new com.artipie.http.rt.RtRulePath(
                        com.artipie.http.rt.RtRule.FALLBACK,
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
                    cfg.groupMemberTimeout().orElse(120L)
                );
                // Create audit slice that aggregates results from ALL members
                // This is critical for vulnerability scanning - local repos return {},
                // but proxy repos return actual vulnerabilities from upstream
                // CRITICAL: Pass member NAMES so GroupAuditSlice can rewrite paths!
                final java.util.List<String> auditMemberNames = cfg.members();
                final java.util.List<Slice> auditMemberSlices = auditMemberNames.stream()
                    .map(name -> this.slice(new Key.From(name), port, 0))
                    .collect(java.util.stream.Collectors.toList());
                final Slice npmGroupAuditSlice = new com.artipie.npm.http.audit.GroupAuditSlice(
                    auditMemberNames, auditMemberSlices
                );
                // npm-group: audit anonymous, user management blocked, all other operations require auth
                slice = trimPathSlice(
                    new com.artipie.http.rt.SliceRoute(
                        // Audit - anonymous, uses GroupAuditSlice to aggregate from all members
                        new com.artipie.http.rt.RtRulePath(
                            new com.artipie.http.rt.RtRule.All(
                                com.artipie.http.rt.MethodRule.POST,
                                new com.artipie.http.rt.RtRule.ByPath(".*/-/npm/v1/security/.*")
                            ),
                            npmGroupAuditSlice
                        ),
                        // Block login/adduser/whoami - group is read-only
                        // NOTE: Do NOT block generic /auth paths - they conflict with scoped packages
                        // like @verdaccio/auth. Standard NPM auth uses /-/user/ and /-/v1/login.
                        new com.artipie.http.rt.RtRulePath(
                            new com.artipie.http.rt.RtRule.Any(
                                new com.artipie.http.rt.RtRule.ByPath(".*/-/v1/login.*"),
                                new com.artipie.http.rt.RtRule.ByPath(".*/-/user/.*"),
                                new com.artipie.http.rt.RtRule.ByPath(".*/-/whoami.*")
                            ),
                            new com.artipie.http.slice.SliceSimple(
                                com.artipie.http.ResponseBuilder.forbidden()
                                    .textBody("User management not supported on group. Use local npm repository.")
                                    .build()
                            )
                        ),
                        // All other operations - require JWT
                        new com.artipie.http.rt.RtRulePath(
                            com.artipie.http.rt.RtRule.FALLBACK,
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
                slice = trimPathSlice(
                    new CombinedAuthzSliceWrap(
                        new ComposerGroupSlice(this::slice, cfg.name(), cfg.members(), port),
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
                    cfg.groupMemberTimeout().orElse(120L)
                );
                slice = trimPathSlice(
                    new CombinedAuthzSliceWrap(
                        new com.artipie.group.MavenGroupSlice(
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
                            cfg.groupMemberTimeout().orElse(120L)
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
                // Use streaming browsing for fast directory listings
                slice = trimPathSlice(
                    new com.artipie.http.slice.BrowsableSlice(
                        new DebianSlice(
                            cfg.storage(), securityPolicy(), authentication(),
                            new com.artipie.debian.Config.FromYaml(cfg.name(), cfg.settings(), settings.configStorage()),
                            artifactEvents()
                        ),
                        cfg.storage()
                    )
                );
                break;
            case "conda":
                // Use streaming browsing for fast directory listings
                slice = new com.artipie.http.slice.BrowsableSlice(
                    new CondaSlice(
                        cfg.storage(), securityPolicy(), authentication(), tokens,
                        cfg.url().toString(), cfg.name(), artifactEvents()
                    ),
                    cfg.storage()
                );
                break;
            case "conan":
                // Use streaming browsing for fast directory listings
                slice = new com.artipie.http.slice.BrowsableSlice(
                    new ConanSlice(
                        cfg.storage(), securityPolicy(), authentication(), tokens,
                        new ItemTokenizer(Vertx.vertx()), cfg.name()
                    ),
                    cfg.storage()
                );
                break;
            case "hexpm":
                // Use streaming browsing for fast directory listings
                slice = trimPathSlice(
                    new com.artipie.http.slice.BrowsableSlice(
                        new HexSlice(cfg.storage(), securityPolicy(), authentication(),
                            artifactEvents(), cfg.name()),
                        cfg.storage()
                    )
                );
                break;
            case "pypi":
                // Use streaming browsing for fast directory listings
                slice = trimPathSlice(
                    new com.artipie.http.slice.BrowsableSlice(
                        new PathPrefixStripSlice(
                            new com.artipie.pypi.http.PySlice(
                                cfg.storage(), securityPolicy(), authentication(),
                                cfg.name(), artifactEvents()
                            ),
                            "simple"
                        ),
                        cfg.storage()
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
        } catch (RuntimeException | Error ex) {
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
        final Slice withMetrics = new com.artipie.http.slice.RepoMetricsSlice(
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
            private final AtomicInteger references = new AtomicInteger(0);
            private final AtomicBoolean metricsRegistered = new AtomicBoolean(false);

            SharedClient(final HttpClientSettingsKey key) {
                this.key = key;
                this.client = new JettyClientSlices(key.toSettings());
                this.client.start();
            }

            void retain() {
                this.references.incrementAndGet();
            }

            int release() {
                final int remaining = this.references.decrementAndGet();
                if (remaining < 0) {
                    throw new IllegalStateException("Jetty client reference count became negative");
                }
                return remaining;
            }

            JettyClientSlices client() {
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
