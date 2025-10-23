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
import com.artipie.http.client.jetty.JettyClientSlices;
import com.artipie.http.filter.FilterSlice;
import com.artipie.http.filter.Filters;
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
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import io.vertx.core.Vertx;

import java.net.URI;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
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
        this.slices = CacheBuilder.newBuilder()
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .removalListener(
                (RemovalListener<SliceKey, SliceValue>) notification -> notification.getValue()
                    .client()
                    .ifPresent(JettyClientSlices::stop)
            )
            .build(
                new CacheLoader<>() {
                    @Override
                    public SliceValue load(final SliceKey key) {
                        // Should not normally be used as we avoid caching NOT_FOUND entries
                        return resolve(key.name(), key.port()).orElseGet(
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

    public Slice slice(final Key name, final int port) {
        final SliceKey skey = new SliceKey(name, port);
        final SliceValue cached = this.slices.getIfPresent(skey);
        if (cached != null) {
            return cached.slice();
        }
        final Optional<SliceValue> resolved = resolve(name, port);
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
     * @return Slice for repo
     */
    private Optional<SliceValue> resolve(final Key name, final int port) {
        Optional<RepoConfig> opt = repos.config(name.string());
        if (opt.isEmpty()) {
            // Attempt to refresh repositories on miss to support runtime additions
            repos.refresh();
            opt = repos.config(name.string());
        }
        if (opt.isPresent()) {
            final RepoConfig cfg = opt.get();
            if (cfg.port().isEmpty() || cfg.port().getAsInt() == port) {
                return Optional.of(sliceFromConfig(cfg, port));
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

    private SliceValue sliceFromConfig(final RepoConfig cfg, final int port) {
        final Slice slice;
        JettyClientSlices clientSlices = null;
        switch (cfg.type()) {
            case "file":
                slice = trimPathSlice(
                    new FilesSlice(
                        cfg.storage(),
                        securityPolicy(),
                        authentication(),
                        tokens.auth(),
                        cfg.name(),
                        artifactEvents()
                    )
                );
                break;
            case "file-proxy":
                clientSlices = jettyClientSlices(cfg);
                slice = trimPathSlice(
                    new TimeoutSlice(
                        new FileProxy(clientSlices, cfg, artifactEvents(), this.cooldown),
                        settings.httpClientSettings().proxyTimeout()
                    )
                );
                break;
            case "npm":
                slice = trimPathSlice(
                    new NpmSlice(
                        cfg.url(), cfg.storage(), securityPolicy(), authentication(), tokens.auth(), cfg.name(), artifactEvents(), true  // JWT-only, no npm tokens
                    )
                );
                break;
            case "gem":
                slice = trimPathSlice(
                    new GemSlice(
                        cfg.storage(),
                        securityPolicy(),
                        authentication(),
                        tokens.auth(),
                        cfg.name(),
                        artifactEvents()
                    )
                );
                break;
            case "helm":
                slice = trimPathSlice(
                    new HelmSlice(
                        cfg.storage(), cfg.url().toString(), securityPolicy(), authentication(), tokens.auth(), cfg.name(), artifactEvents()
                    )
                );
                break;
            case "rpm":
                slice = trimPathSlice(
                    new RpmSlice(cfg.storage(), securityPolicy(), authentication(),
                        tokens.auth(), new com.artipie.rpm.RepoConfig.FromYaml(cfg.settings(), cfg.name()), Optional.empty())
                );
                break;
            case "php":
                slice = trimPathSlice(
                    new PhpComposer(
                        new AstoRepository(cfg.storage(), Optional.of(cfg.url().toString())),
                        securityPolicy(), authentication(), tokens.auth(), cfg.name(), artifactEvents()
                    )
                );
                break;
            case "php-proxy":
                clientSlices = jettyClientSlices(cfg);
                slice = trimPathSlice(
                    new TimeoutSlice(
                        new ComposerProxy(
                            clientSlices,
                            cfg,
                            settings.artifactMetadata().flatMap(queues -> queues.proxyEventQueues(cfg)),
                            this.cooldown
                        ),
                        settings.httpClientSettings().proxyTimeout()
                    )
                );
                break;
            case "nuget":
                slice = trimPathSlice(
                    new NuGet(
                        cfg.url(), new com.artipie.nuget.AstoRepository(cfg.storage()),
                        securityPolicy(), authentication(), tokens.auth(), cfg.name(), artifactEvents()
                    )
                );
                break;
            case "gradle":
                slice = trimPathSlice(
                    new GradleSlice(cfg.storage(), securityPolicy(),
                        authentication(), cfg.name(), artifactEvents())
                );
                break;
            case "gradle-proxy":
                clientSlices = jettyClientSlices(cfg);
                slice = trimPathSlice(
                    new CombinedAuthzSliceWrap(
                        new TimeoutSlice(
                            new GradleProxy(
                                clientSlices,
                                cfg,
                                settings.artifactMetadata().flatMap(queues -> queues.proxyEventQueues(cfg)),
                                this.cooldown
                            ).slice(),
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
            case "maven":
                slice = trimPathSlice(
                    new MavenSlice(cfg.storage(), securityPolicy(),
                        authentication(), tokens.auth(), cfg.name(), artifactEvents())
                );
                break;
            case "maven-proxy":
                clientSlices = jettyClientSlices(cfg);
                slice = trimPathSlice(
                    new CombinedAuthzSliceWrap(
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
                    )
                );
                break;
            case "go":
                slice = trimPathSlice(
                    new GoSlice(
                        cfg.storage(),
                        securityPolicy(),
                        authentication(),
                        tokens.auth(),
                        cfg.name(),
                        artifactEvents()
                    )
                );
                break;
            case "go-proxy":
                clientSlices = jettyClientSlices(cfg);
                slice = trimPathSlice(
                    new CombinedAuthzSliceWrap(
                        new TimeoutSlice(
                            new GoProxy(
                                clientSlices,
                                cfg,
                                settings.artifactMetadata().flatMap(queues -> queues.proxyEventQueues(cfg)),
                                this.cooldown
                            ).slice(),
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
                clientSlices = jettyClientSlices(cfg);
                final URI npmRemoteUri = URI.create(
                    cfg.settings().orElseThrow().yamlMapping("remote").string("url")
                );
                final NpmProxy npmProxy = new NpmProxy(
                    npmRemoteUri, cfg.storage(), clientSlices
                );
                final Slice npmProxySlice = new TimeoutSlice(
                    new NpmProxySlice(
                        cfg.path(),
                        npmProxy,
                        settings.artifactMetadata().flatMap(queues -> queues.proxyEventQueues(cfg)),
                        cfg.name(),
                        cfg.type(),
                        this.cooldown,
                        new com.artipie.http.client.UriClientSlice(clientSlices, npmRemoteUri),
                        Optional.of(cfg.url())
                    ),
                    settings.httpClientSettings().proxyTimeout()
                );
                // npm-proxy routing: audit anonymous (via SecurityAuditProxySlice), login blocked, downloads require JWT
                slice = new com.artipie.http.rt.SliceRoute(
                    // Audit - anonymous, SecurityAuditProxySlice already strips headers
                    new com.artipie.http.rt.RtRulePath(
                        new com.artipie.http.rt.RtRule.All(
                            com.artipie.http.rt.MethodRule.POST,
                            new com.artipie.http.rt.RtRule.ByPath(".*/-/npm/v1/security/.*")
                        ),
                        npmProxySlice
                    ),
                    // Block login/adduser/whoami - proxy is read-only
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
                );
                break;
            case "npm-group":
                final Slice npmGroupSlice = new GroupSlice(this::slice, cfg.name(), cfg.members(), port);
                // npm-group: audit anonymous, all other operations require auth
                slice = trimPathSlice(
                    new com.artipie.http.rt.SliceRoute(
                        // Audit - anonymous
                        new com.artipie.http.rt.RtRulePath(
                            new com.artipie.http.rt.RtRule.All(
                                com.artipie.http.rt.MethodRule.POST,
                                new com.artipie.http.rt.RtRule.ByPath(".*/-/npm/v1/security/.*")
                            ),
                            npmGroupSlice
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
            case "gem-group":
            case "go-group":
            case "gradle-group":
            case "maven-group":
            case "pypi-group":
            case "docker-group":
                slice = trimPathSlice(
                    new CombinedAuthzSliceWrap(
                        new GroupSlice(this::slice, cfg.name(), cfg.members(), port),
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
                clientSlices = jettyClientSlices(cfg);
                slice = trimPathSlice(
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
                clientSlices = jettyClientSlices(cfg);
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
                        new Config.FromYaml(cfg.name(), cfg.settings(), settings.configStorage()),
                        artifactEvents()
                    )
                );
                break;
            case "conda":
                slice = new CondaSlice(
                    cfg.storage(), securityPolicy(), authentication(),
                    tokens, cfg.url().toString(), cfg.name(), artifactEvents()
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
                    new com.artipie.pypi.http.PySlice(
                        cfg.storage(), securityPolicy(), authentication(), 
                        tokens.auth(), cfg.name(), artifactEvents()
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
            Optional.ofNullable(clientSlices)
        );
    }

    private Slice wrapIntoCommonSlices(
        final Slice origin,
        final RepoConfig cfg
    ) {
        Optional<Filters> opt = settings.caches()
            .filtersCache()
            .filters(cfg.name(), cfg.repoYaml());
        Slice res = opt.isPresent() ? new FilterSlice(origin, opt.get()) : origin;
        return cfg.contentLengthMax()
            .<Slice>map(limit -> new ContentLengthRestriction(res, limit))
            .orElse(res);
    }

    private Authentication authentication() {
        return new LoggingAuth(settings.authz().authentication());
    }

    private Policy<?> securityPolicy() {
        return this.settings.authz().policy();
    }

    private JettyClientSlices jettyClientSlices(final RepoConfig cfg) {
        JettyClientSlices res = new JettyClientSlices(
            cfg.httpClientSettings().orElseGet(settings::httpClientSettings)
        );
        res.start();
        return res;
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
    record SliceValue(Slice slice, Optional<JettyClientSlices> client) {
    }
}
