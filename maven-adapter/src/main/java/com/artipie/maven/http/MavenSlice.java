/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.maven.http;

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
import com.artipie.http.slice.SliceSimple;
import com.artipie.maven.metadata.ArtifactEventInfo;
import com.artipie.scheduling.ArtifactEvent;
import com.artipie.security.perms.Action;
import com.artipie.security.perms.AdapterBasicPermission;
import com.artipie.security.policy.Policy;

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
     * Private ctor since Artipie doesn't know about `Identities` implementation.
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
                    new LocalMavenSlice(storage),
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
