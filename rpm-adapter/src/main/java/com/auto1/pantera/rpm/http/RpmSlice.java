/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.rpm.http;

import com.artipie.asto.Storage;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.auth.Authentication;
import com.artipie.http.auth.BasicAuthzSlice;
import com.artipie.http.auth.CombinedAuthzSliceWrap;
import com.artipie.http.auth.OperationControl;
import com.artipie.http.auth.TokenAuthentication;
import com.artipie.http.rt.MethodRule;
import com.artipie.http.rt.RtRule;
import com.artipie.http.rt.RtRulePath;
import com.artipie.http.rt.SliceRoute;
import com.artipie.http.slice.SliceDownload;
import com.artipie.http.slice.StorageArtifactSlice;
import com.artipie.http.slice.SliceSimple;
import com.artipie.rpm.RepoConfig;
import com.artipie.scheduling.ArtifactEvent;
import com.artipie.security.perms.Action;
import com.artipie.security.perms.AdapterBasicPermission;
import com.artipie.security.policy.Policy;

import java.util.Optional;
import java.util.Queue;

/**
 * Artipie {@link Slice} for RPM repository HTTP API.
 * @since 0.7
 */
public final class RpmSlice extends Slice.Wrap {

    /**
     * Ctor.
     * @param storage Storage
     * @param policy Access policy.
     * @param auth Auth details.
     * @param config Repository configuration.
     * @param events Artifact events queue
     */
    public RpmSlice(
        final Storage storage,
        final Policy<?> policy,
        final Authentication auth,
        final RepoConfig config,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        this(storage, policy, auth, null, config, events);
    }

    /**
     * Ctor with both basic and token authentication support.
     * @param storage Storage
     * @param policy Access policy.
     * @param basicAuth Basic authentication.
     * @param tokenAuth Token authentication.
     * @param config Repository configuration.
     * @param events Artifact events queue
     */
    public RpmSlice(
        final Storage storage,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final RepoConfig config,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        super(
            RpmSlice.createSliceRoute(storage, policy, basicAuth, tokenAuth, config, events)
        );
    }

    /**
     * Creates slice route with appropriate authentication.
     * @param storage Storage
     * @param policy Access policy
     * @param basicAuth Basic authentication
     * @param tokenAuth Token authentication
     * @param config Repository configuration
     * @param events Artifact events queue
     * @return Slice route
     */
    private static SliceRoute createSliceRoute(
        final Storage storage,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final RepoConfig config,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        return new SliceRoute(
            new RtRulePath(
                MethodRule.GET,
                RpmSlice.createAuthSlice(
                    new StorageArtifactSlice(storage),
                    basicAuth,
                    tokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(config.name(), Action.Standard.READ)
                    )
                )
            ),
            new RtRulePath(
                MethodRule.PUT,
                RpmSlice.createAuthSlice(
                    new RpmUpload(storage, config, events),
                    basicAuth,
                    tokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(config.name(), Action.Standard.WRITE)
                    )
                )
            ),
            new RtRulePath(
                MethodRule.DELETE,
                RpmSlice.createAuthSlice(
                    new RpmRemove(storage, config, events),
                    basicAuth,
                    tokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(config.name(), Action.Standard.READ)
                    )
                )
            ),
            new RtRulePath(RtRule.FALLBACK, new SliceSimple(ResponseBuilder.notFound().build()))
        );
    }

    /**
     * Creates appropriate authentication slice based on available authentication methods.
     * @param origin Origin slice
     * @param basicAuth Basic authentication
     * @param tokenAuth Token authentication
     * @param control Access control
     * @return Authentication slice
     */
    private static Slice createAuthSlice(
        final Slice origin,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final OperationControl control
    ) {
        if (tokenAuth != null) {
            return new CombinedAuthzSliceWrap(origin, basicAuth, tokenAuth, control);
        } else {
            return new BasicAuthzSlice(origin, basicAuth, control);
        }
    }
}
