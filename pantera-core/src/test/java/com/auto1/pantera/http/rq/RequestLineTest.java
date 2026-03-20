/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.rq;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Ensure that {@link RequestLine} works correctly.
 *
 * @since 0.1
 */
public class RequestLineTest {

    @Test
    public void reqLineStringIsCorrect() {
        MatcherAssert.assertThat(
            new RequestLine("GET", "/pub/WWW/TheProject.html", "HTTP/1.1").toString(),
            Matchers.equalTo("GET /pub/WWW/TheProject.html HTTP/1.1")
        );
    }

    @Test
    public void shouldHaveDefaultVersionWhenNoneSpecified() {
        MatcherAssert.assertThat(
            new RequestLine(RqMethod.PUT, "/file.txt").toString(),
            Matchers.equalTo("PUT /file.txt HTTP/1.1")
        );
    }
    
    @Test
    public void handlesIllegalCharactersInPath() {
        // Real-world case: Maven artifacts with unresolved properties
        final RequestLine line = new RequestLine(
            "GET",
            "/libs-release/wkda/common/commons-static/${commons-support.version}/commons-static-${commons-support.version}.pom"
        );
        MatcherAssert.assertThat(
            "Should not throw IllegalArgumentException",
            line.uri().getPath(),
            Matchers.containsString("commons-static")
        );
    }
    
    @Test
    public void handlesOtherIllegalCharacters() {
        // Test other commonly problematic characters
        final RequestLine line = new RequestLine(
            "GET",
            "/path/with/pipe|char/and\\backslash"
        );
        MatcherAssert.assertThat(
            "Should handle pipes and backslashes",
            line.method(),
            Matchers.equalTo(RqMethod.GET)
        );
    }
    
    @Test
    public void handlesLowercaseHttpMethods() {
        // Real-world case: Some clients send lowercase HTTP methods
        final RequestLine line = new RequestLine(
            "get",
            "/path/to/resource"
        );
        MatcherAssert.assertThat(
            "Should accept lowercase HTTP methods",
            line.method(),
            Matchers.equalTo(RqMethod.GET)
        );
    }
    
    @Test
    public void handlesMixedCaseHttpMethods() {
        final RequestLine line = new RequestLine(
            "Post",
            "/api/endpoint"
        );
        MatcherAssert.assertThat(
            "Should normalize mixed case HTTP methods",
            line.method(),
            Matchers.equalTo(RqMethod.POST)
        );
    }
    
    @Test
    public void handlesMalformedDoubleSlashUri() {
        // Real-world case: Some clients send "//" which URI parser interprets
        // as start of authority section (hostname) but with no authority
        final RequestLine line = new RequestLine(
            "GET",
            "//"
        );
        MatcherAssert.assertThat(
            "Should handle '//' without throwing exception",
            line.uri().getPath(),
            Matchers.equalTo("/")
        );
    }
    
    @Test
    public void handlesEmptyUri() {
        final RequestLine line = new RequestLine(
            "GET",
            ""
        );
        MatcherAssert.assertThat(
            "Should handle empty URI",
            line.uri().getPath(),
            Matchers.equalTo("/")
        );
    }
    
    @Test
    public void handlesMavenVersionRangesWithBrackets() {
        // Real-world case: Maven version ranges like [release] in artifact paths
        final RequestLine line = new RequestLine(
            "GET",
            "/artifactory/libs-release-local/wkda/common/graphql/retail-inventory-gql/[release]/retail-inventory-gql-[release].graphql"
        );
        MatcherAssert.assertThat(
            "Should handle square brackets in path",
            line.uri().getPath(),
            Matchers.containsString("retail-inventory-gql")
        );
    }
}
