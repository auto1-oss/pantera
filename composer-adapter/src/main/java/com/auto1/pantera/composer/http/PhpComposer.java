/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.composer.http;

import com.auto1.pantera.composer.Repository;
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
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;
import java.util.Optional;
import java.util.Queue;
import java.util.regex.Pattern;

/**
 * PHP Composer repository HTTP front end.
 *
 * @since 0.1
 */
public final class PhpComposer extends Slice.Wrap {
    /**
     * Ctor.
     * @param repository Repository
     * @param policy Access permissions
     * @param auth Authentication
     * @param name Repository name
     * @param events Artifact repository events
     */
    public PhpComposer(
        final Repository repository,
        final Policy<?> policy,
        final Authentication auth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        this(repository, policy, auth, null, name, events);
    }

    /**
     * Ctor with combined authentication support.
     * @param repository Repository
     * @param policy Access permissions
     * @param basicAuth Basic authentication
     * @param tokenAuth Token authentication
     * @param name Repository name
     * @param events Artifact repository events
     */
    public PhpComposer(
        final Repository repository,
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
                        new RtRule.Any(
                            new RtRule.ByPath(PackageMetadataSlice.PACKAGE),
                            new RtRule.ByPath(PackageMetadataSlice.ALL_PACKAGES)
                        ),
                        MethodRule.GET
                    ),
                    PhpComposer.createAuthSlice(
                        new PackageMetadataSlice(repository),
                        basicAuth,
                        tokenAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.READ)
                        )
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.ByPath(Pattern.compile("^/?artifacts/.*\\.(zip|tar\\.gz|tgz)$")),
                        MethodRule.GET
                    ),
                    PhpComposer.createAuthSlice(
                        new DownloadArchiveSlice(repository),
                        basicAuth,
                        tokenAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.READ)
                        )
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.ByPath(Pattern.compile("^/.*\\.(zip|tar\\.gz|tgz)$")),
                        MethodRule.GET
                    ),
                    PhpComposer.createAuthSlice(
                        new DownloadArchiveSlice(repository),
                        basicAuth,
                        tokenAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.READ)
                        )
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.ByPath(AddSlice.PATH_PATTERN),
                        MethodRule.PUT
                    ),
                    PhpComposer.createAuthSlice(
                        new AddSlice(repository),
                        basicAuth,
                        tokenAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.WRITE)
                        )
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.ByPath(".*\\.(zip|tar\\.gz|tgz)$"),
                        MethodRule.PUT
                    ),
                    PhpComposer.createAuthSlice(
                        new AddArchiveSlice(repository, events, name),
                        basicAuth,
                        tokenAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.WRITE)
                        )
                    )
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
