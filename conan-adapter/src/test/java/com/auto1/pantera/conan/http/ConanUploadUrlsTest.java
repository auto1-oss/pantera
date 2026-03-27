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
package  com.auto1.pantera.conan.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.conan.ItemTokenizer;
import com.auto1.pantera.conan.ItemTokenizer.ItemInfo;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.hm.IsJson;
import com.auto1.pantera.http.hm.RsHasBody;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.RsStatus;
import io.vertx.core.Vertx;
import org.hamcrest.Description;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.TypeSafeMatcher;
import org.junit.jupiter.api.Test;
import wtf.g4s8.hamcrest.json.JsonHas;

import javax.json.JsonValue;
import javax.json.JsonValue.ValueType;
import java.nio.charset.StandardCharsets;

/**
 * Test for {@link ConanUpload}.
 */
public class ConanUploadUrlsTest {

    @Test
    void tokenizerTest() {
        final String path = "/test/path/to/file";
        final String host = "test_hostname.com";
        final ItemTokenizer tokenizer = new ItemTokenizer(Vertx.vertx());
        final String token = tokenizer.generateToken(path, host);
        final ItemInfo item = tokenizer.authenticateToken(token).toCompletableFuture().join().orElseThrow();
        MatcherAssert.assertThat("Decoded path must match", item.getPath().equals(path));
        MatcherAssert.assertThat("Decoded host must match", item.getHostname().equals(host));
    }

    @Test
    void uploadsUrlsKeyByPath() throws Exception {
        final Storage storage = new InMemoryStorage();
        final String payload =
            "{\"conan_export.tgz\": \"\", \"conanfile.py\":\"\", \"conanmanifest.txt\": \"\"}";
        final byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        final Response response = new ConanUpload.UploadUrls(storage, new ItemTokenizer(Vertx.vertx()))
            .response(
            new RequestLine(
                "POST", "/v1/conans/zmqpp/4.2.0/_/_/upload_urls"
            ),
            Headers.from(
                new Header("Content-Size", Long.toString(data.length)),
                new Header("Host", "localhost")
            ),
            new Content.From(data)
            ).join();
        MatcherAssert.assertThat(
            "Response body must match",
            response,
            Matchers.allOf(
                new RsHasStatus(RsStatus.OK),
                new RsHasBody(
                    new IsJson(
                        Matchers.allOf(
                            new JsonHas(
                                "conan_export.tgz",
                                new JsonValueStarts("http://localhost/zmqpp/4.2.0/_/_/0/export/conan_export.tgz?signature=")
                            ),
                            new JsonHas(
                                "conanfile.py",
                                new JsonValueStarts(
                                    "http://localhost/zmqpp/4.2.0/_/_/0/export/conanfile.py?signature="
                                )
                            ),
                            new JsonHas(
                                "conanmanifest.txt",
                                new JsonValueStarts(
                                    "http://localhost/zmqpp/4.2.0/_/_/0/export/conanmanifest.txt?signature="
                                )
                            )
                        )
                    )
                )
            )
        );
    }

    /**
     * Checks that json string value start with the prefix provided.
     * @since 0.1
     */
    private static class JsonValueStarts extends TypeSafeMatcher<JsonValue> {

        /**
         * Prefix string value for matching.
         */
        private final String prefix;

        /**
         * Creates json prefix matcher with provided prefix.
         * @param prefix Prefix string value.
         */
        JsonValueStarts(final String prefix) {
            this.prefix = prefix;
        }

        @Override
        public void describeTo(final Description desc) {
            desc.appendText("prefix: ")
                .appendValue(this.prefix)
                .appendText(" of type ")
                .appendValue(ValueType.STRING);
        }

        @Override
        protected boolean matchesSafely(final JsonValue item) {
            return item.getValueType().equals(ValueType.STRING) &&
                item.toString().startsWith(this.prefix, 1);
        }
    }
}
