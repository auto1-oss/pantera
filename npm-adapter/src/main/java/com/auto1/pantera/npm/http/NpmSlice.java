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
package com.auto1.pantera.npm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.BearerAuthzSlice;
import com.auto1.pantera.http.auth.CombinedAuthzSliceWrap;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.auth.Tokens;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rt.MethodRule;
import com.auto1.pantera.http.rt.RtRule;
import com.auto1.pantera.http.rt.RtRulePath;
import com.auto1.pantera.http.rt.SliceRoute;
import com.auto1.pantera.http.slice.StorageArtifactSlice;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.npm.http.auth.AddUserSlice;
import com.auto1.pantera.npm.http.auth.PanteraAddUserSlice;
import com.auto1.pantera.npm.http.auth.NpmTokenAuthentication;
import com.auto1.pantera.npm.http.auth.WhoAmISlice;
import com.auto1.pantera.npm.http.search.SearchSlice;
import com.auto1.pantera.npm.repository.StorageUserRepository;
import com.auto1.pantera.npm.repository.StorageTokenRepository;
import com.auto1.pantera.npm.security.BCryptPasswordHasher;
import com.auto1.pantera.npm.security.TokenGenerator;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;

import java.net.URL;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

/**
 * NpmSlice is a http layer in npm adapter.
 */
public final class NpmSlice implements Slice {

    /**
     * Header name `npm-command`.
     */
    private static final String NPM_COMMAND = "npm-command";

    /**
     * Header name `referer`.
     */
    private static final String REFERER = "referer";

    /**
     * Route.
     */
    private final SliceRoute route;

    /**
     * Token service (optional, used for JWT-only logins).
     */
    private final Tokens tokens;

    /**
     * Ctor.
     *
     * @param base Base URL.
     * @param storage Storage for package.
     * @param policy Access permissions.
     * @param auth Authentication.
     * @param name Repository name
     * @param events Events queue
     */
    public NpmSlice(
        final URL base,
        final Storage storage,
        final Policy<?> policy,
        final TokenAuthentication auth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        this(base, storage, policy, null, auth, name, events);
    }

    /**
     * Ctor with combined authentication support.
     *
     * @param base Base URL.
     * @param storage Storage for package.
     * @param policy Access permissions.
     * @param basicAuth Basic authentication.
     * @param tokenAuth Token authentication.
     * @param name Repository name
     * @param events Events queue
     */
    public NpmSlice(
        final URL base,
        final Storage storage,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        this(base, storage, policy, basicAuth, tokenAuth, name, events, false, null,
            com.auto1.pantera.index.SyncArtifactIndexer.NOOP, ArtifactIndex.NOP);
    }

    /**
     * Ctor with JWT-only option.
     * @param base Base URL.
     * @param storage Storage for package.
     * @param policy Access permissions.
     * @param basicAuth Basic authentication.
     * @param tokenAuth Token authentication (Keycloak JWT).
     * @param name Repository name
     * @param events Events queue
     * @param jwtOnly If true, use only JWT auth (no npm-specific tokens)
     */
    public NpmSlice(
        final URL base,
        final Storage storage,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events,
        final boolean jwtOnly
    ) {
        this(base, storage, policy, basicAuth, tokenAuth, name, events, jwtOnly, null,
            com.auto1.pantera.index.SyncArtifactIndexer.NOOP, ArtifactIndex.NOP);
    }

    /**
     * Ctor with JWT-only option and token service.
     *
     * @param base Base URL.
     * @param storage Storage for package.
     * @param policy Access permissions.
     * @param basicAuth Basic authentication.
     * @param tokenAuth Token authentication.
     * @param tokens Token service
     * @param name Repository name
     * @param events Events queue
     * @param jwtOnly If true, use only JWT auth (no npm-specific tokens)
     */
    public NpmSlice(
        final URL base,
        final Storage storage,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final Tokens tokens,
        final String name,
        final Optional<Queue<ArtifactEvent>> events,
        final boolean jwtOnly
    ) {
        this(base, storage, policy, basicAuth, tokenAuth, name, events, jwtOnly, tokens,
            com.auto1.pantera.index.SyncArtifactIndexer.NOOP, ArtifactIndex.NOP);
    }

    /**
     * Ctor with synchronous artifact-index writer and the shared search index.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public NpmSlice(
        final URL base,
        final Storage storage,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final Tokens tokens,
        final String name,
        final Optional<Queue<ArtifactEvent>> events,
        final boolean jwtOnly,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex,
        final ArtifactIndex artifactIndex
    ) {
        this(base, storage, policy, basicAuth, tokenAuth, name, events, jwtOnly, tokens,
            syncIndex, artifactIndex);
    }

    /**
     * Primary ctor.
     * @param base Base URL.
     * @param storage Storage.
     * @param policy Policy.
     * @param basicAuth Basic auth.
     * @param tokenAuth Token auth.
     * @param name Repository name.
     * @param events Events queue.
     * @param jwtOnly Use JWT-only mode.
     * @param tokens Token service (optional).
     * @param syncIndex Synchronous artifact-index writer.
     * @param artifactIndex Shared search index backing {@code /-/v1/search}.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private NpmSlice(
        final URL base,
        final Storage storage,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events,
        final boolean jwtOnly,
        final Tokens tokens,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex,
        final ArtifactIndex artifactIndex
    ) {
        this.tokens = tokens;
        final TokenAuthentication npmTokenAuth = jwtOnly
            ? tokenAuth
            : new NpmTokenAuthentication(new StorageTokenRepository(storage), tokenAuth);

        this.route = new SliceRoute(
            // SECURITY: reserved internal keys (the registry signing keypair,
            // user/token records) live in this same repository Storage; block
            // them from the raw content routes here, first, before any content
            // route or auth can serve them. See ReservedKeyGuardSlice.
            new RtRulePath(
                new RtRule.ByPath(ReservedKeyGuardSlice.RESERVED_PATH),
                new ReservedKeyGuardSlice()
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.GET,
                    // WS8 Bug B4: the repository root, once TrimPathSlice
                    // (wired one layer up, in RepositorySlices) has already
                    // stripped the repository-name segment -- always a bare
                    // "/" (never a literal "/npm", which only "matched" by
                    // the accident of a repo being named exactly "npm" plus
                    // a spurious extra "/npm" path segment).
                    new RtRule.ByPath("^/?$")
                ),
                NpmSlice.createAuthSlice(
                    new RegistryInfoSlice(name),
                    basicAuth,
                    npmTokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.READ)
                    )
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.GET,
                    new RtRule.ByPath(".*/-/ping$")
                ),
                NpmSlice.createAuthSlice(
                    new PingSlice(),
                    basicAuth,
                    npmTokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.READ)
                    )
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.GET,
                    new RtRule.ByPath(com.auto1.pantera.npm.http.auth.NpmrcAuthSlice.AUTH_SCOPE_PATTERN)
                ),
                NpmSlice.createAuthSlice(
                    new com.auto1.pantera.npm.http.auth.NpmrcAuthSlice(
                        base,
                        basicAuth,
                        this.tokens,
                        npmTokenAuth
                    ),
                    basicAuth,
                    npmTokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.READ)
                    )
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.GET,
                    new RtRule.ByPath(com.auto1.pantera.npm.http.auth.NpmrcAuthSlice.AUTH_PATTERN)
                ),
                NpmSlice.createAuthSlice(
                    new com.auto1.pantera.npm.http.auth.NpmrcAuthSlice(
                        base,
                        basicAuth,
                        this.tokens,
                        npmTokenAuth
                    ),
                    basicAuth,
                    npmTokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.READ)
                    )
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.PUT,
                    new RtRule.ByPath(AddDistTagsSlice.PTRN)
                ),
                NpmSlice.createAuthSlice(
                    new AddDistTagsSlice(storage),
                    basicAuth,
                    npmTokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.WRITE)
                    )
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.DELETE,
                    new RtRule.ByPath(AddDistTagsSlice.PTRN)
                ),
                NpmSlice.createAuthSlice(
                    new DeleteDistTagsSlice(storage),
                    basicAuth,
                    npmTokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.DELETE)
                    )
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.PUT,
                    new RtRule.Any(
                        new RtRule.ByHeader(NpmSlice.NPM_COMMAND, CliPublish.HEADER),
                        new RtRule.ByHeader(NpmSlice.REFERER, CliPublish.HEADER)
                    )
                ),
                NpmSlice.createAuthSlice(
                    new UploadSlice(new CliPublish(storage), storage, events, name, syncIndex),
                    basicAuth,
                    npmTokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.WRITE)
                    )
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.PUT,
                    new RtRule.Any(
                        new RtRule.ByHeader(NpmSlice.NPM_COMMAND, DeprecateSlice.HEADER),
                        new RtRule.ByHeader(NpmSlice.REFERER, DeprecateSlice.HEADER)
                    )
                ),
                NpmSlice.createAuthSlice(
                    new DeprecateSlice(storage),
                    basicAuth,
                    npmTokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.WRITE)
                    )
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.PUT,
                    new RtRule.Any(
                        new RtRule.ByHeader(NpmSlice.NPM_COMMAND, UnpublishPutSlice.HEADER),
                        new RtRule.ByHeader(NpmSlice.REFERER, UnpublishPutSlice.HEADER)
                    )
                ),
                NpmSlice.createAuthSlice(
                    new UnpublishPutSlice(storage, events, name),
                    basicAuth,
                    npmTokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.DELETE)
                    )
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.PUT,
                    new RtRule.ByPath(CurlPublish.PTRN)
                ),
                NpmSlice.createAuthSlice(
                    new UploadSlice(new CurlPublish(storage), storage, events, name, syncIndex),
                    basicAuth,
                    npmTokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.WRITE)
                    )
                )
            ),
            // Catch-all PUT route for package publish (lerna, pnpm, etc. that don't send headers)
            // Matches: /@scope/package or /package (but not .tgz files - already handled above)
            new RtRulePath(
                new RtRule.All(
                    MethodRule.PUT,
                    new RtRule.ByPath("^/(@[^/]+/)?[^/]+$")  // Matches package names, not paths with /
                ),
                NpmSlice.createAuthSlice(
                    new UploadSlice(new CliPublish(storage), storage, events, name, syncIndex),
                    basicAuth,
                    npmTokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.WRITE)
                    )
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.GET,
                    new RtRule.ByPath(".*/dist-tags$")
                ),
                NpmSlice.createAuthSlice(
                    new GetDistTagsSlice(storage),
                    basicAuth,
                    npmTokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.READ)
                    )
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.POST,
                    new RtRule.ByPath(".*/-/npm/v1/security/.*")
                ),
                // Use LocalAuditSlice (returns empty) - anonymous access
                new com.auto1.pantera.npm.http.audit.LocalAuditSlice()
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.PUT,
                    new RtRule.ByPath(".*/-/user/org\\.couchdb\\.user:.+")
                ),
                // Use JWT-only OAuth login or npm token-based adduser
                jwtOnly && basicAuth != null
                    ? new com.auto1.pantera.npm.http.auth.OAuthLoginSlice(basicAuth, this.tokens)  // JWT-only
                    : (basicAuth != null 
                        ? new PanteraAddUserSlice(  // Creates npm tokens
                            basicAuth,
                            new StorageTokenRepository(storage),
                            new TokenGenerator()
                        )
                        : new AddUserSlice(  // Standalone npm tokens
                            new StorageUserRepository(storage, new BCryptPasswordHasher()),
                            new StorageTokenRepository(storage),
                            new BCryptPasswordHasher(),
                            new TokenGenerator()
                        ))
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.GET,
                    new RtRule.ByPath(".*/-/whoami")
                ),
                jwtOnly
                    ? NpmSlice.createAuthSlice(  // JWT-only whoami
                        new com.auto1.pantera.npm.http.auth.JwtWhoAmISlice(),
                        basicAuth,
                        npmTokenAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.READ)
                        )
                    )
                    : NpmSlice.createAuthSlice(  // Old whoami with npm tokens
                        new WhoAmISlice(),
                        basicAuth,
                        npmTokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.READ)
                    )
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.GET,
                    new RtRule.ByPath(".*/-/v1/search")
                ),
                NpmSlice.createAuthSlice(
                    new SearchSlice(artifactIndex, name),
                    basicAuth,
                    npmTokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.READ)
                    )
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.POST,
                    new RtRule.ByPath(".*/-/v1/login$")
                ),
                this.webLoginSlice(jwtOnly, basicAuth)
            ),
            new RtRulePath(
                new RtRule.All(
                    new RtRule.Any(MethodRule.GET, MethodRule.POST),
                    new RtRule.ByPath(".*/-/npm/v1/tokens$")
                ),
                NpmSlice.createAuthSlice(
                    new DeclinedEndpointSlice(
                        "npm token management",
                        "repositories/npm.md#unsupported-endpoints"
                    ),
                    basicAuth,
                    npmTokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.READ)
                    )
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.DELETE,
                    new RtRule.ByPath(".*/-/npm/v1/tokens/token/.+$")
                ),
                NpmSlice.createAuthSlice(
                    new DeclinedEndpointSlice(
                        "npm token management",
                        "repositories/npm.md#unsupported-endpoints"
                    ),
                    basicAuth,
                    npmTokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.WRITE)
                    )
                )
            ),
            // WS-A: npm hook and npm team have no supported surface at all
            // (any method); npm org is only declined for its write verbs --
            // GET (e.g. "npm org ls") is a genuine passthrough on proxy and
            // group repositories and must keep falling through to the
            // package routes below. See repositories/npm.md#unsupported-endpoints.
            new RtRulePath(
                new RtRule.ByPath(".*/-/npm/v1/hooks.*"),
                this.declinedRoute(
                    "npm registry webhooks", Action.Standard.READ,
                    basicAuth, npmTokenAuth, policy, name
                )
            ),
            new RtRulePath(
                new RtRule.ByPath(".*/-/team/.*"),
                this.declinedRoute(
                    "npm team management", Action.Standard.READ,
                    basicAuth, npmTokenAuth, policy, name
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    new RtRule.Any(MethodRule.PUT, MethodRule.POST, MethodRule.DELETE),
                    new RtRule.ByPath(".*/-/org/.*")
                ),
                this.declinedRoute(
                    "npm organization management", Action.Standard.WRITE,
                    basicAuth, npmTokenAuth, policy, name
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.GET,
                    new RtRule.ByPath(".*/-/npm/v1/user$")
                ),
                NpmSlice.createAuthSlice(
                    NpmSlice.profileSlice(jwtOnly, storage),
                    basicAuth,
                    npmTokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.READ)
                    )
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.PUT,
                    new RtRule.ByPath(".*/-/npm/v1/user$")
                ),
                NpmSlice.createAuthSlice(
                    NpmSlice.profileSlice(jwtOnly, storage),
                    basicAuth,
                    npmTokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.WRITE)
                    )
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.GET,
                    new RtRule.ByPath(".*/-/npm/v1/attestations/.+$")
                ),
                NpmSlice.createAuthSlice(
                    new com.auto1.pantera.npm.http.attestation.AttestationsSlice(
                        new com.auto1.pantera.npm.http.attestation.AttestationStore(storage), name
                    ),
                    basicAuth,
                    npmTokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.READ)
                    )
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.GET,
                    new RtRule.ByPath(".*/-/npm/v1/keys$")
                ),
                NpmSlice.createAuthSlice(
                    new com.auto1.pantera.npm.http.attestation.KeysSlice(
                        new com.auto1.pantera.npm.security.NpmSigningKeys(storage), name
                    ),
                    basicAuth,
                    npmTokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.READ)
                    )
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    new RtRule.Any(MethodRule.GET, MethodRule.HEAD),
                    // WS8 Bug B1: the old pattern "^/(@[^/]+/)?[^/]+/[^/]+$"
                    // let its optional scope group not participate, so
                    // "[^/]+/[^/]+" alone matched a bare 2-segment scoped
                    // PACKAGE NAME ("/@scope/pkg") as if it were
                    // package+version ("/pkg/version") -- routing a
                    // packument request here, where SingleVersionSlice#parse
                    // correctly refuses that shape and 404s instead of
                    // letting it fall through to the packument route below.
                    // Mirrors SingleVersionSlice#parse exactly: an unscoped
                    // pair is 2 segments whose first does not start with
                    // "@"; a scoped pair is 3 segments whose first does.
                    new RtRule.ByPath("^/(?:@[^/]+/[^/]+/[^/]+|[^/@][^/]*/[^/]+)$")
                ),
                NpmSlice.createAuthSlice(
                    new SingleVersionSlice(base, storage, name),
                    basicAuth,
                    npmTokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.READ)
                    )
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    new RtRule.Any(MethodRule.GET, MethodRule.HEAD),
                    new RtRule.ByPath(".*\\.json$")
                ),
                NpmSlice.createAuthSlice(
                    new StorageArtifactSlice(storage),
                    basicAuth,
                    npmTokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.READ)
                    )
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    new RtRule.Any(MethodRule.GET, MethodRule.HEAD),
                    new RtRule.ByPath(".*(?<!\\.tgz)$")
                ),
                NpmSlice.createAuthSlice(
                    new DownloadPackageSlice(base, storage),
                    basicAuth,
                    npmTokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.READ)
                    )
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    new RtRule.Any(MethodRule.GET, MethodRule.HEAD),
                    new RtRule.ByPath(".*\\.tgz$")
                ),
                NpmSlice.createAuthSlice(
                    new StorageArtifactSlice(storage),
                    basicAuth,
                    npmTokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.READ)
                    )
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.DELETE,
                    new RtRule.ByPath(UnpublishForceSlice.PTRN)
                ),
                NpmSlice.createAuthSlice(
                    new UnpublishForceSlice(storage, events, name),
                    basicAuth,
                    npmTokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.DELETE)
                    )
                )
            )
        );
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body) {
        return this.route.response(line, headers, body);
    }

    /**
     * Creates appropriate auth slice based on available authentication methods.
     * @param origin Original slice to wrap
     * @param basicAuth Basic authentication
     * @param tokenAuth Token authentication
     * @param control Operation control
     * @return Auth slice
     */
    private static Slice createAuthSlice(
        final Slice origin, final Authentication basicAuth,
        final TokenAuthentication tokenAuth, final OperationControl control
    ) {
        if (basicAuth != null) {
            return new CombinedAuthzSliceWrap(origin, basicAuth, tokenAuth, control);
        }
        return new BearerAuthzSlice(origin, tokenAuth, control);
    }

    /**
     * Web login (`npm login --auth-type=web`) is only meaningful for
     * JWT-only repos with a Pantera authentication backend wired; other
     * modes keep the pre-existing 404 (no route existed before this).
     *
     * @param jwtOnly Whether this repository is JWT-only
     * @param basicAuth Basic authentication, or {@code null}
     * @return Login slice
     */
    private Slice webLoginSlice(final boolean jwtOnly, final Authentication basicAuth) {
        final Slice slice;
        if (jwtOnly && basicAuth != null) {
            slice = new com.auto1.pantera.npm.http.auth.OAuthLoginSlice(basicAuth, this.tokens);
        } else {
            slice = new SliceSimple(ResponseBuilder.notFound().build());
        }
        return slice;
    }

    /**
     * Wraps a {@link DeclinedEndpointSlice} the same way every other route
     * in this class wraps its handler: shared auth, then a permission
     * check against {@code name}. Pulled out purely to keep the declined
     * npm-platform routes (webhooks, team, organization writes) from
     * lengthening the primary constructor further -- see {@code
     * repositories/npm.md#unsupported-endpoints}.
     *
     * @param feature Human-readable feature name for the decline message
     * @param action Permission required to reach the decline response
     * @param basicAuth Basic authentication
     * @param tokenAuth Token authentication
     * @param policy Access permissions
     * @param name Repository name
     * @return Auth-wrapped declined-endpoint slice
     * @checkstyle ParameterNumberCheck (3 lines)
     */
    private Slice declinedRoute(
        final String feature,
        final Action action,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final Policy<?> policy,
        final String name
    ) {
        return NpmSlice.createAuthSlice(
            new DeclinedEndpointSlice(feature, "repositories/npm.md#unsupported-endpoints"),
            basicAuth,
            tokenAuth,
            new OperationControl(policy, new AdapterBasicPermission(name, action))
        );
    }

    /**
     * {@code /-/npm/v1/user} is enriched with a stored email address outside
     * JWT-only mode; JWT-only repositories have no local user record.
     *
     * @param jwtOnly Whether this repository is JWT-only
     * @param storage Repository storage
     * @return Profile slice
     */
    private static Slice profileSlice(final boolean jwtOnly, final Storage storage) {
        final Slice slice;
        if (jwtOnly) {
            slice = new com.auto1.pantera.npm.http.auth.ProfileSlice();
        } else {
            slice = new com.auto1.pantera.npm.http.auth.ProfileSlice(
                new StorageUserRepository(storage, new BCryptPasswordHasher())
            );
        }
        return slice;
    }
}
