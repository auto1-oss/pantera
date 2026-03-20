/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */

package com.auto1.pantera.pypi.http;

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.BasicAuthzSlice;
import com.auto1.pantera.http.auth.CombinedAuthzSliceWrap;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.rt.MethodRule;
import com.auto1.pantera.http.rt.RtRule;
import com.auto1.pantera.http.rt.RtRulePath;
import com.auto1.pantera.http.rt.SliceRoute;
import com.auto1.pantera.http.slice.SliceDownload;
import com.auto1.pantera.http.slice.StorageArtifactSlice;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.http.slice.SliceWithHeaders;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;

import java.util.Optional;
import java.util.Queue;
import java.util.regex.Pattern;

/**
 * PyPi HTTP entry point.
 */
public final class PySlice extends Slice.Wrap {

    /**
     * Primary ctor.
     * @param storage The storage.
     * @param policy Access policy.
     * @param auth Concrete identities.
     * @param name Repository name
     * @param queue Events queue
     */
    public PySlice(
        final Storage storage,
        final Policy<?> policy,
        final Authentication auth,
        final String name,
        final Optional<Queue<ArtifactEvent>> queue
    ) {
        this(storage, policy, auth, null, name, queue);
    }

    /**
     * Ctor with combined authentication support.
     * @param storage The storage.
     * @param policy Access policy.
     * @param basicAuth Basic authentication.
     * @param tokenAuth Token authentication.
     * @param name Repository name
     * @param queue Events queue
     */
    public PySlice(
        final Storage storage,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> queue
    ) {
        super(
            new SliceRoute(
                new RtRulePath(
                    new RtRule.All(
                        MethodRule.GET,
                        new RtRule.ByPath(".*\\.(whl|tar\\.gz|zip|tar\\.bz2|tar\\.Z|tar|egg)")
                    ),
                    PySlice.createAuthSlice(
                        new SliceWithHeaders(
                            new StorageArtifactSlice(storage),
                            Headers.from(ContentType.mime("application/octet-stream"))
                        ),
                        basicAuth,
                        tokenAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.READ)
                        )
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        MethodRule.POST,
                        new RtRule.ByHeader(
                            "content-type", Pattern.compile("multipart.*", Pattern.CASE_INSENSITIVE)
                        )
                    ),
                    PySlice.createAuthSlice(
                        new WheelSlice(storage, queue, name),
                        basicAuth,
                        tokenAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.WRITE)
                        )
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        MethodRule.POST,
                        new RtRule.ByHeader(
                            "content-type", Pattern.compile("text.*", Pattern.CASE_INSENSITIVE)
                        )
                    ),
                    PySlice.createAuthSlice(
                        new SearchSlice(storage),
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
                        new RtRule.ByPath("(^\\/)|(.*(\\/[a-z0-9\\-]+?\\/?$))")
                    ),
                    new BasicAuthzSlice(
                        new SliceIndex(storage),
                        basicAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.READ)
                        )
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        MethodRule.GET
                    ),
                    PySlice.createAuthSlice(
                        new RedirectSlice(),
                        basicAuth,
                        tokenAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.READ)
                        )
                    )
                ),
                new RtRulePath(
                    MethodRule.DELETE,
                    PySlice.createAuthSlice(
                        new DeleteSlice(storage),
                        basicAuth,
                        tokenAuth,
                        new OperationControl(
                                policy,
                                new AdapterBasicPermission(name, Action.Standard.WRITE)
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
            return new CombinedAuthzSliceWrap(origin, basicAuth, tokenAuth, control);
        }
        return new BasicAuthzSlice(origin, basicAuth, control);
    }
}
