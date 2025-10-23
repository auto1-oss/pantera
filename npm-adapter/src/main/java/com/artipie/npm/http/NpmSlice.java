/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */

package com.artipie.npm.http;

import com.artipie.asto.Content;
import com.artipie.asto.Storage;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.auth.AuthUser;
import com.artipie.http.auth.BearerAuthzSlice;
import com.artipie.http.auth.CombinedAuthzSliceWrap;
import com.artipie.http.auth.OperationControl;
import com.artipie.http.auth.TokenAuthentication;
import com.artipie.http.auth.Authentication;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rt.MethodRule;
import com.artipie.http.rt.RtRule;
import com.artipie.http.rt.RtRulePath;
import com.artipie.http.rt.SliceRoute;
import com.artipie.http.slice.SliceDownload;
import com.artipie.http.slice.SliceSimple;
import com.artipie.npm.http.auth.AddUserSlice;
import com.artipie.npm.http.auth.ArtipieAddUserSlice;
import com.artipie.npm.http.auth.NpmTokenAuthentication;
import com.artipie.npm.http.auth.WhoAmISlice;
import com.artipie.npm.http.search.SearchSlice;
import com.artipie.npm.http.search.InMemoryPackageIndex;
import com.artipie.npm.repository.StorageUserRepository;
import com.artipie.npm.repository.StorageTokenRepository;
import com.artipie.npm.security.BCryptPasswordHasher;
import com.artipie.npm.security.TokenGenerator;
import com.artipie.scheduling.ArtifactEvent;
import com.artipie.security.perms.Action;
import com.artipie.security.perms.AdapterBasicPermission;
import com.artipie.security.policy.Policy;

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
     * Ctor with existing front and default parameters for free access.
     * @param base Base URL.
     * @param storage Storage for package
     */
    public NpmSlice(final URL base, final Storage storage) {
        this(base, storage, Policy.FREE, NpmSlice.ANONYMOUS, "*", Optional.empty());
    }

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
        this(base, storage, policy, basicAuth, tokenAuth, name, events, false);
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
        // Use either JWT-only or npm token auth with JWT fallback
        final TokenAuthentication npmTokenAuth = jwtOnly 
            ? tokenAuth  // JWT only
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
                new com.artipie.npm.http.audit.LocalAuditSlice()
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.PUT,
                    new RtRule.ByPath(".*/-/user/org\\.couchdb\\.user:.+")
                ),
                // Use JWT-only OAuth login or npm token-based adduser
                jwtOnly && basicAuth != null
                    ? new com.artipie.npm.http.auth.OAuthLoginSlice(basicAuth)  // JWT-only
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
                        new com.artipie.npm.http.auth.JwtWhoAmISlice(),
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
                    new SliceDownload(storage),
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
