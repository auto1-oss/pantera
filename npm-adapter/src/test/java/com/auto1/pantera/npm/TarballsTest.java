/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.npm;

import com.auto1.pantera.asto.Concatenation;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Remaining;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import javax.json.Json;
import javax.json.JsonObject;
import org.apache.commons.io.IOUtils;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests tarballs processing.
 * @since 0.6
 */
public class TarballsTest {
    /**
     * Do actual tests with processing data.
     * @param prefix Tarball prefix
     * @param expected Expected absolute tarball link
     * @throws IOException
     */
    @ParameterizedTest
    @CsvSource({
        "http://example.com/, http://example.com/@hello/simple-npm-project/-/@hello/simple-npm-project-1.0.1.tgz",
        "http://example.com/context/path, http://example.com/context/path/@hello/simple-npm-project/-/@hello/simple-npm-project-1.0.1.tgz"
    })
    public void tarballsProcessingWorks(final String prefix, final String expected)
        throws IOException {
        final byte[] data = IOUtils.resourceToByteArray(
            "/storage/@hello/simple-npm-project/meta.json"
        );
        final Tarballs tarballs = new Tarballs(
            new Content.From(data),
            URI.create(prefix).toURL()
        );
        final Content modified = tarballs.value();
        final JsonObject json = new Concatenation(modified)
            .single()
            .map(buf -> new Remaining(buf).bytes())
            .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
            .map(StringReader::new)
            .map(reader -> Json.createReader(reader).readObject())
            .blockingGet();
        MatcherAssert.assertThat(
            json.getJsonObject("versions").getJsonObject("1.0.1")
                .getJsonObject("dist").getString("tarball"),
            new IsEqual<>(expected)
        );
    }

    /**
     * Test that malformed URLs (with embedded absolute URLs) are fixed.
     * This handles metadata that was created before the fix was applied.
     * @throws IOException On error
     */
    @ParameterizedTest
    @CsvSource({
        "http://localhost:8081/npm, http://localhost:8081/test_prefix/api/npm/@wkda/npm-proxy/-/@wkda/npm-proxy-1.4.0.tgz, http://localhost:8081/npm/@wkda/npm-proxy/-/@wkda/npm-proxy-1.4.0.tgz",
        "http://localhost:8081/npm, /test_prefix/api/npm/@scope/pkg/-/@scope/pkg-1.0.0.tgz, http://localhost:8081/npm/@scope/pkg/-/@scope/pkg-1.0.0.tgz"
    })
    public void fixesMalformedAbsoluteUrls(
        final String prefix,
        final String malformedUrl,
        final String expected
    ) throws IOException {
        // Create test metadata with malformed URL
        final String metaJson = String.format(
            "{\"versions\":{\"1.0.0\":{\"dist\":{\"tarball\":\"%s\"}}}}",
            malformedUrl
        );
        final Tarballs tarballs = new Tarballs(
            new Content.From(metaJson.getBytes(StandardCharsets.UTF_8)),
            URI.create(prefix).toURL()
        );
        final Content modified = tarballs.value();
        final JsonObject json = new Concatenation(modified)
            .single()
            .map(buf -> new Remaining(buf).bytes())
            .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
            .map(StringReader::new)
            .map(reader -> Json.createReader(reader).readObject())
            .blockingGet();
        MatcherAssert.assertThat(
            "Should fix malformed URL",
            json.getJsonObject("versions").getJsonObject("1.0.0")
                .getJsonObject("dist").getString("tarball"),
            new IsEqual<>(expected)
        );
    }
}
