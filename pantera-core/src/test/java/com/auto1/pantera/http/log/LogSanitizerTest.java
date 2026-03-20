/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.log;

import com.auto1.pantera.http.Headers;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LogSanitizer}.
 */
final class LogSanitizerTest {

    @Test
    void sanitizesBearerToken() {
        final String result = LogSanitizer.sanitizeAuthHeader(
            "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
        );
        MatcherAssert.assertThat(
            result,
            Matchers.equalTo("Bearer ***REDACTED***")
        );
    }

    @Test
    void sanitizesBasicAuth() {
        final String result = LogSanitizer.sanitizeAuthHeader(
            "Basic dXNlcm5hbWU6cGFzc3dvcmQ="
        );
        MatcherAssert.assertThat(
            result,
            Matchers.equalTo("Basic ***REDACTED***")
        );
    }

    @Test
    void sanitizesUrlWithApiKey() {
        final String result = LogSanitizer.sanitizeUrl(
            "https://api.example.com/data?api_key=secret123&other=value"
        );
        MatcherAssert.assertThat(
            result,
            Matchers.allOf(
                Matchers.containsString("api_key=***REDACTED***"),
                Matchers.containsString("other=value"),
                Matchers.not(Matchers.containsString("secret123"))
            )
        );
    }

    @Test
    void sanitizesHeaders() {
        final Headers headers = Headers.from("Authorization", "Bearer secret_token")
            .copy()
            .add("Content-Type", "application/json")
            .add("X-API-Key", "my-api-key-123");
        
        final Headers sanitized = LogSanitizer.sanitizeHeaders(headers);
        
        MatcherAssert.assertThat(
            "Authorization should be masked",
            sanitized.values("Authorization").stream().findFirst().orElse(""),
            Matchers.equalTo("Bearer ***REDACTED***")
        );
        
        MatcherAssert.assertThat(
            "Content-Type should not be masked",
            sanitized.values("Content-Type").stream().findFirst().orElse(""),
            Matchers.equalTo("application/json")
        );
        
        MatcherAssert.assertThat(
            "X-API-Key should be masked",
            sanitized.values("X-API-Key").stream().findFirst().orElse(""),
            Matchers.equalTo("***REDACTED***")
        );
    }

    @Test
    void sanitizesMessageWithToken() {
        final String result = LogSanitizer.sanitizeMessage(
            "Request failed with Authorization: Bearer abc123xyz"
        );
        MatcherAssert.assertThat(
            result,
            Matchers.allOf(
                Matchers.containsString("Bearer ***REDACTED***"),
                Matchers.not(Matchers.containsString("abc123xyz"))
            )
        );
    }

    @Test
    void preservesNonSensitiveData() {
        final String url = "https://maven.example.com/repo/artifact.jar";
        MatcherAssert.assertThat(
            LogSanitizer.sanitizeUrl(url),
            Matchers.equalTo(url)
        );
    }
}
