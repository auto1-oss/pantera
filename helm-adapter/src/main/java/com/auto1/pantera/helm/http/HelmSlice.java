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

import com.auto1.pantera.asto.Storage;
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
import com.auto1.pantera.http.slice.SliceDownload;
import com.auto1.pantera.http.slice.StorageArtifactSlice;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;

import java.util.Optional;
import java.util.Queue;

/**
 * HelmSlice.
 * @since 0.1
 */
public final class HelmSlice extends Slice.Wrap {

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
        this(storage, base, policy, auth, null, name, events);
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
        super(
            new SliceRoute(
                new RtRulePath(
                    new RtRule.Any(
                        MethodRule.PUT, MethodRule.POST
                    ),
                    HelmSlice.createAuthSlice(
                        new PushChartSlice(storage, events, name),
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
                        new StorageArtifactSlice(storage),
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
