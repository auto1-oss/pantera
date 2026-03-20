/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.debian.http;

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.debian.Config;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.BasicAuthzSlice;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.rt.MethodRule;
import com.auto1.pantera.http.rt.RtRule;
import com.auto1.pantera.http.rt.RtRulePath;
import com.auto1.pantera.http.rt.SliceRoute;
import com.auto1.pantera.http.slice.*;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;

import java.util.Optional;
import java.util.Queue;
import java.util.regex.Pattern;

/**
 * Debian slice.
 */
public final class DebianSlice extends Slice.Wrap {

    /**
     * Ctor.
     * @param storage Storage
     * @param policy Policy
     * @param users Users
     * @param config Repository configuration
     * @param events Artifact events queue
     */
    public DebianSlice(
            final Storage storage,
            final Policy<?> policy,
            final Authentication users,
            final Config config,
            final Optional<Queue<ArtifactEvent>> events
    ) {
        super(
            new SliceRoute(
                new RtRulePath(
                    MethodRule.GET,
                    new BasicAuthzSlice(
                        new ReleaseSlice(new StorageArtifactSlice(storage), storage, config),
                        users,
                        new OperationControl(
                            policy,
                            new AdapterBasicPermission(config.codename(), Action.Standard.READ)
                        )
                    )
                ),
                new RtRulePath(
                    new RtRule.Any(
                        MethodRule.PUT, MethodRule.POST
                    ),
                    new BasicAuthzSlice(
                        new ReleaseSlice(new UpdateSlice(storage, config, events), storage, config),
                        users,
                        new OperationControl(
                            policy,
                            new AdapterBasicPermission(config.codename(), Action.Standard.WRITE)
                        )
                    )
                ),
                new RtRulePath(
                    MethodRule.DELETE,
                    new BasicAuthzSlice(
                        new DeleteSlice(storage, config),
                        users,
                        new OperationControl(
                            policy,
                            new AdapterBasicPermission(config.codename(), Action.Standard.WRITE)
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
