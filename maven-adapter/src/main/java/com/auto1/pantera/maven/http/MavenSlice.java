/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.maven.http;

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
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.maven.metadata.ArtifactEventInfo;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;

import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.regex.Pattern;

/**
 * Maven API entry point.
 * @since 0.1
 */
public final class MavenSlice extends Slice.Wrap {

    /**
     * Instance of {@link ArtifactEventInfo}.
     */
    public static final ArtifactEventInfo EVENT_INFO = new ArtifactEventInfo();

    /**
     * Supported artifacts extensions. According to
     * <a href="https://maven.apache.org/ref/3.6.3/maven-core/artifact-handlers.html">Artifact
     * handlers</a> by maven-core and <a href="https://maven.apache.org/pom.html">Maven docs</a>.
     */
    public static final List<String> EXT =
        List.of("jar", "war", "maven-plugin", "ejb", "ear", "rar", "zip", "aar", "pom");

    /**
     * Pattern to obtain artifact name and version from key. The regex DOES NOT match
     * checksum files, xmls, javadoc and sources archives. Uses list of supported extensions
     * from above.
     */
    public static final Pattern ARTIFACT = Pattern.compile(
        String.format(
            "^(?<pkg>.+)/.+(?<!sources|javadoc)\\.(?<ext>%s)$", String.join("|", MavenSlice.EXT)
        )
    );

    /**
     * Private ctor since Pantera doesn't know about `Identities` implementation.
     * @param storage The storage.
     * @param policy Access policy.
     * @param users Concrete identities.
     * @param name Repository name
     * @param events Artifact events
     */
    public MavenSlice(
        final Storage storage,
        final Policy<?> policy,
        final Authentication users,
        final String name,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        this(storage, policy, users, null, name, events);
    }

    /**
     * Ctor with both basic and token authentication support.
     * @param storage The storage.
     * @param policy Access policy.
     * @param basicAuth Basic authentication.
     * @param tokenAuth Token authentication.
     * @param name Repository name
     * @param events Artifact events
     */
    public MavenSlice(
        final Storage storage,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        super(
            MavenSlice.createSliceRoute(storage, policy, basicAuth, tokenAuth, name, events)
        );
    }

    /**
     * Creates slice route with appropriate authentication.
     * @param storage The storage
     * @param policy Access policy
     * @param basicAuth Basic authentication
     * @param tokenAuth Token authentication
     * @param name Repository name
     * @param events Artifact events
     * @return Slice route
     */
    private static SliceRoute createSliceRoute(
        final Storage storage,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        return new SliceRoute(
            new RtRulePath(
                new RtRule.Any(
                    MethodRule.GET, MethodRule.HEAD
                ),
                MavenSlice.createAuthSlice(
                    new LocalMavenSlice(storage, name),
                    basicAuth,
                    tokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.READ)
                    )
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.PUT,
                    new RtRule.ByPath(".*SNAPSHOT.*")
                ),
                MavenSlice.createAuthSlice(
                    new UploadSlice(storage, events, name),
                    basicAuth,
                    tokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.WRITE)
                    )
                )
            ),
            new RtRulePath(
                MethodRule.PUT,
                MavenSlice.createAuthSlice(
                    new UploadSlice(storage, events, name),
                    basicAuth,
                    tokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.WRITE)
                    )
                )
            ),
            new RtRulePath(
                RtRule.FALLBACK, new SliceSimple(ResponseBuilder.notFound().build())
            )
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
