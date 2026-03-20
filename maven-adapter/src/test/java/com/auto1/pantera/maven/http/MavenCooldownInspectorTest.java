/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.cooldown.CooldownDependency;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

final class MavenCooldownInspectorTest {

    @Test
    void includesParentChainInDependencies() {
        final Map<String, String> poms = Map.of(
            "/com/example/app/1.0/app-1.0.pom",
            """
            <project xmlns=\"http://maven.apache.org/POM/4.0.0\">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>app</artifactId>
              <version>1.0</version>
              <dependencies>
                <dependency>
                  <groupId>com.example</groupId>
                  <artifactId>dep-one</artifactId>
                  <version>5.0</version>
                </dependency>
              </dependencies>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>1.0</version>
              </parent>
            </project>
            """,
            "/com/example/parent/1.0/parent-1.0.pom",
            """
            <project xmlns=\"http://maven.apache.org/POM/4.0.0\">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>parent</artifactId>
              <version>1.0</version>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>ancestor</artifactId>
                <version>2.0</version>
              </parent>
            </project>
            """,
            "/com/example/ancestor/2.0/ancestor-2.0.pom",
            """
            <project xmlns=\"http://maven.apache.org/POM/4.0.0\">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>ancestor</artifactId>
              <version>2.0</version>
            </project>
            """
        );
        final MavenCooldownInspector inspector = new MavenCooldownInspector(new PomSlice(poms));
        final List<CooldownDependency> deps = inspector.dependencies("com.example.app", "1.0").join();
        final List<String> coordinates = deps.stream()
            .map(dep -> dep.artifact() + ":" + dep.version())
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            coordinates,
            Matchers.contains(
                "com.example.dep-one:5.0",
                "com.example.parent:1.0",
                "com.example.ancestor:2.0"
            )
        );
    }

    private static final class PomSlice implements Slice {

        private final Map<String, String> poms;

        private PomSlice(final Map<String, String> poms) {
            this.poms = poms;
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line,
            final Headers headers,
            final Content body
        ) {
            final String path = line.uri().getPath();
            final String pom = this.poms.get(path);
            if (!this.poms.containsKey(path)) {
                return ResponseBuilder.notFound().completedFuture();
            }
            if (line.method() == RqMethod.GET) {
                return ResponseBuilder.ok().textBody(pom).completedFuture();
            }
            if (line.method() == RqMethod.HEAD) {
                return ResponseBuilder.ok().completedFuture();
            }
            return ResponseBuilder.methodNotAllowed().completedFuture();
        }
    }
}
