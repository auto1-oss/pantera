/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */

package com.auto1.pantera.npm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.AuthUser;
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
import com.auto1.pantera.http.slice.SliceDownload;
import com.auto1.pantera.http.slice.StorageArtifactSlice;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.npm.http.auth.AddUserSlice;
import com.auto1.pantera.npm.http.auth.ArtipieAddUserSlice;
import com.auto1.pantera.npm.http.auth.NpmTokenAuthentication;
import com.auto1.pantera.npm.http.auth.WhoAmISlice;
import com.auto1.pantera.npm.http.search.SearchSlice;
import com.auto1.pantera.npm.http.search.InMemoryPackageIndex;
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
 *
 * @todo #340:30min Implement `/npm` endpoint properly: for now `/npm` simply returns 200 OK
 *  status without any body. We need to figure out what information can (or should) be returned
 *  by registry on this request and add it. Here are several links that might be useful
 *  https://github.com/npm/cli
 *  https://github.com/npm/registry
 *  https://docs.npmjs.com/cli/v8
 */
@SuppressWarnings("PMD.ExcessiveMethodLength")
public final class NpmSlice implements Slice {

    /**
     * Anonymous token auth for test purposes.
     */
    static final TokenAuthentication ANONYMOUS = tkn
        -> CompletableFuture.completedFuture(Optional.of(new AuthUser("anonymous", "anonymity")));

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
     * Ctor with existing front and default parameters for free access.
     * @param base Base URL.
     * @param storage Storage for package
     * @param events Events queue
     */
    public NpmSlice(final URL base, final Storage storage, final Queue<ArtifactEvent> events) {
        this(base, storage, Policy.FREE, NpmSlice.ANONYMOUS, "*", Optional.of(events));
    }

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
        this(base, storage, policy, basicAuth, tokenAuth, name, events, false, null);
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
        this(base, storage, policy, basicAuth, tokenAuth, name, events, jwtOnly, null);
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
        this(base, storage, policy, basicAuth, tokenAuth, name, events, jwtOnly, tokens);
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
        final Tokens tokens
    ) {
        this.tokens = tokens;
        final TokenAuthentication npmTokenAuth = jwtOnly
            ? tokenAuth
            : new NpmTokenAuthentication(new StorageTokenRepository(storage), tokenAuth);

        this.route = new SliceRoute(
            new RtRulePath(
                new RtRule.All(
                    MethodRule.GET,
                    new RtRule.ByPath("/npm")
                ),
                NpmSlice.createAuthSlice(
                    new SliceSimple(ResponseBuilder.ok().build()),
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
                        policy, new AdapterBasicPermission(name, Action.Standard.WRITE)
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
                    new UploadSlice(new CliPublish(storage), storage, events, name),
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
                        policy, new AdapterBasicPermission(name, Action.Standard.WRITE)
                    )
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.PUT,
                    new RtRule.ByPath(CurlPublish.PTRN)
                ),
                NpmSlice.createAuthSlice(
                    new UploadSlice(new CurlPublish(storage), storage, events, name),
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
                    new UploadSlice(new CliPublish(storage), storage, events, name),
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
                        ? new ArtipieAddUserSlice(  // Creates npm tokens
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
                    new SearchSlice(storage, new InMemoryPackageIndex()),
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
                    MethodRule.GET,
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
                    MethodRule.GET,
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
}
