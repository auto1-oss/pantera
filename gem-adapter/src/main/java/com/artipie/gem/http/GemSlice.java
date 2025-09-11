/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.gem.http;

import com.artipie.asto.Storage;
import com.artipie.gem.GemApiKeyAuth;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.auth.Authentication;
import com.artipie.http.auth.AuthzSlice;
import com.artipie.http.auth.CombinedAuthScheme;
import com.artipie.http.auth.OperationControl;
import com.artipie.http.auth.TokenAuthentication;
import com.artipie.http.rt.MethodRule;
import com.artipie.http.rt.RtRule;
import com.artipie.http.rt.RtRulePath;
import com.artipie.http.rt.SliceRoute;
import com.artipie.http.slice.SliceDownload;
import com.artipie.http.slice.SliceSimple;
import com.artipie.scheduling.ArtifactEvent;
import com.artipie.security.perms.Action;
import com.artipie.security.perms.AdapterBasicPermission;
import com.artipie.security.policy.Policy;

import java.util.Optional;
import java.util.Queue;

/**
 * A slice, which servers gem packages.
 * Ruby HTTP layer.
 */
@SuppressWarnings({"PMD.UnusedPrivateField", "PMD.SingularField"})
public final class GemSlice extends Slice.Wrap {

    /**
     * @param storage The storage.
     * @param policy The policy.
     * @param auth The auth.
     * @param name Repository name
     */
    public GemSlice(Storage storage, Policy<?> policy, Authentication auth, String name) {
        this(storage, policy, auth, null, name, Optional.empty());
    }

    /**
     * Ctor.
     *
     * @param storage The storage.
     * @param policy The policy.
     * @param auth The auth.
     * @param name Repository name
     * @param events Artifact events queue
     */
    public GemSlice(
        final Storage storage,
        final Policy<?> policy,
        final Authentication auth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        this(storage, policy, auth, null, name, events);
    }

    /**
     * Ctor with combined authentication support.
     *
     * @param storage The storage.
     * @param policy The policy.
     * @param basicAuth Basic authentication.
     * @param tokenAuth Token authentication.
     * @param name Repository name
     * @param events Artifact events queue
     */
    public GemSlice(
        final Storage storage,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        super(
            new SliceRoute(
                new RtRulePath(
                    new RtRule.All(
                        MethodRule.POST,
                        new RtRule.ByPath("/api/v1/gems")
                    ),
                    GemSlice.createAuthSlice(
                        new SubmitGemSlice(storage, events, name),
                        basicAuth,
                        tokenAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.WRITE)
                        )
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        MethodRule.GET,
                        new RtRule.ByPath("/api/v1/dependencies")
                    ),
                    new DepsGemSlice(storage)
                ),
                new RtRulePath(
                    new RtRule.All(
                        MethodRule.GET,
                        new RtRule.ByPath("/api/v1/api_key")
                    ),
                    new ApiKeySlice(basicAuth)
                ),
                new RtRulePath(
                    new RtRule.All(
                        MethodRule.GET,
                        new RtRule.ByPath(ApiGetSlice.PATH_PATTERN)
                    ),
                    new ApiGetSlice(storage)
                ),
                new RtRulePath(
                    MethodRule.GET,
                    GemSlice.createAuthSlice(
                        new SliceDownload(storage),
                        basicAuth,
                        tokenAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.READ)
                        )
                    )
                ),
                new RtRulePath(
                    RtRule.FALLBACK,
                    new SliceSimple(ResponseBuilder.notFound().build())
                )
            )
        );
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
        if (tokenAuth != null) {
            return new AuthzSlice(origin, new CombinedAuthScheme(basicAuth, tokenAuth), control);
        }
        return new AuthzSlice(origin, new GemApiKeyAuth(basicAuth), control);
    }
}
