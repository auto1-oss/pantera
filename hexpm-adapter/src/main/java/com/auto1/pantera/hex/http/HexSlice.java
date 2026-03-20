/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.hex.http;

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.BasicAuthzSlice;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.rt.MethodRule;
import com.auto1.pantera.http.rt.RtRule;
import com.auto1.pantera.http.rt.RtRulePath;
import com.auto1.pantera.http.rt.SliceRoute;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;

import java.util.Optional;
import java.util.Queue;

/**
 * Pantera {@link Slice} for HexPm repository HTTP API.
 */
public final class HexSlice extends Slice.Wrap {

    /**
     * @param storage The storage for package.
     * @param policy Access policy.
     * @param users Concrete identities.
     * @param events Artifact events queue
     * @param name Repository name
     */
    public HexSlice(final Storage storage, final Policy<?> policy, final Authentication users,
                    final Optional<Queue<ArtifactEvent>> events, final String name) {
        super(new SliceRoute(
                new RtRulePath(
                    new RtRule.All(
                        MethodRule.GET,
                        new RtRule.Any(
                            new RtRule.ByPath(DownloadSlice.PACKAGES_PTRN),
                            new RtRule.ByPath(DownloadSlice.TARBALLS_PTRN)
                        )
                    ),
                    new BasicAuthzSlice(
                        new DownloadSlice(storage),
                        users,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.READ)
                        )
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        MethodRule.GET,
                        new RtRule.ByPath(UserSlice.USERS)
                    ),
                    new BasicAuthzSlice(
                        new UserSlice(),
                        users,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.READ)
                        )
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        MethodRule.POST,
                        new RtRule.ByPath(UploadSlice.PUBLISH)
                    ),
                    new BasicAuthzSlice(
                        new UploadSlice(storage, events, name),
                        users,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.WRITE)
                        )
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        MethodRule.POST,
                        new RtRule.ByPath(DocsSlice.DOCS_PTRN)
                    ),
                    new BasicAuthzSlice(
                        new DocsSlice(),
                        users,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.READ)
                        )
                    )
                ),
                new RtRulePath(
                    RtRule.FALLBACK, new SliceSimple(ResponseBuilder.notFound().build())
                )
            )
        );
    }
}