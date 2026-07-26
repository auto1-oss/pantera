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
package com.auto1.pantera.helm.http;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blob.DownloadPolicy;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.BasicAuthzSlice;
import com.auto1.pantera.http.auth.CombinedAuthzSliceWrap;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.rt.MethodRule;
import com.auto1.pantera.http.rt.RtRule;
import com.auto1.pantera.http.rt.RtRulePath;
import com.auto1.pantera.http.rt.SliceRoute;
import com.auto1.pantera.http.slice.StorageArtifactSlice;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;

import java.util.Optional;
import java.util.Queue;
import java.util.function.Predicate;

/**
 * HelmSlice.
 * @since 0.1
 */
public final class HelmSlice extends Slice.Wrap {

    /**
     * WS1.7 redirect gate for the shared catch-all GET route: only chart
     * tarballs ({@code .tgz}) are redirect-eligible. {@code index.yaml} is
     * served by {@link DownloadIndexSlice} on an earlier route and never
     * reaches here; {@code .prov} provenance signatures fall through to the
     * catch-all but end in {@code .prov}, not {@code .tgz}, so they stream.
     */
    private static final Predicate<Key> REDIRECTABLE =
        key -> key.string().endsWith(".tgz");

    /**
     * Ctor.
     *
     * @param storage The storage.
     * @param base The base path the slice is expected to be accessed from. Example: https://central.pantera.com/helm
     * @param policy Access policy.
     * @param auth Authentication.
     * @param name Repository name
     * @param events Events queue
     */
    public HelmSlice(
        final Storage storage,
        final String base,
        final Policy<?> policy,
        final Authentication auth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        this(storage, base, policy, auth, null, name, events,
            com.auto1.pantera.index.SyncArtifactIndexer.NOOP);
    }

    /**
     * Ctor with combined authentication support.
     *
     * @param storage The storage.
     * @param base The base path the slice is expected to be accessed from. Example: https://central.pantera.com/helm
     * @param policy Access policy.
     * @param basicAuth Basic authentication.
     * @param tokenAuth Token authentication.
     * @param name Repository name
     * @param events Events queue
     */
    public HelmSlice(
        final Storage storage,
        final String base,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        this(storage, base, policy, basicAuth, tokenAuth, name, events,
            com.auto1.pantera.index.SyncArtifactIndexer.NOOP);
    }

    /**
     * Ctor with synchronous artifact-index writer -- stream-only downloads.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public HelmSlice(
        final Storage storage,
        final String base,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex
    ) {
        this(storage, base, policy, basicAuth, tokenAuth, name, events, syncIndex,
            DownloadPolicy.streamOnly());
    }

    /**
     * Ctor with an explicit WS1.7 download policy: chart {@code .tgz} GETs
     * become redirect-eligible under a non-{@link DownloadPolicy#streamOnly()}
     * policy, while {@code index.yaml} and {@code .prov} provenance keep
     * streaming (see {@link #REDIRECTABLE}).
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public HelmSlice(
        final Storage storage,
        final String base,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex,
        final DownloadPolicy downloadPolicy
    ) {
        super(
            new SliceRoute(
                new RtRulePath(
                    new RtRule.Any(
                        MethodRule.PUT, MethodRule.POST
                    ),
                    HelmSlice.createAuthSlice(
                        new PushChartSlice(storage, events, name, syncIndex),
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
                        new RtRule.ByPath(DownloadIndexSlice.PTRN)
                    ),
                    HelmSlice.createAuthSlice(
                        new DownloadIndexSlice(base, storage),
                        basicAuth,
                        tokenAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.READ)
                        )
                    )
                ),
                new RtRulePath(
                    MethodRule.GET,
                    HelmSlice.createAuthSlice(
                        new StorageArtifactSlice(storage, downloadPolicy, HelmSlice.REDIRECTABLE),
                        basicAuth,
                        tokenAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.READ)
                        )
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.ByPath(DeleteChartSlice.PTRN_DEL_CHART),
                        MethodRule.DELETE
                    ),
                    HelmSlice.createAuthSlice(
                        new DeleteChartSlice(storage, events, name),
                        basicAuth,
                        tokenAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.DELETE)
                        )
                    )
                ),
                new RtRulePath(
                    RtRule.FALLBACK,
                    new SliceSimple(ResponseBuilder.methodNotAllowed().build())
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
            return new CombinedAuthzSliceWrap(origin, basicAuth, tokenAuth, control);
        }
        return new BasicAuthzSlice(origin, basicAuth, control);
    }
}
