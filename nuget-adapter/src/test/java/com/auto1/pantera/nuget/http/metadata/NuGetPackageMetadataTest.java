/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.nuget.http.metadata;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.hm.RsHasBody;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.nuget.AstoRepository;
import com.auto1.pantera.nuget.PackageIdentity;
import com.auto1.pantera.nuget.PackageKeys;
import com.auto1.pantera.nuget.Versions;
import com.auto1.pantera.nuget.http.NuGet;
import com.auto1.pantera.nuget.http.TestAuthentication;
import com.auto1.pantera.nuget.metadata.Nuspec;
import com.auto1.pantera.nuget.metadata.Version;
import com.auto1.pantera.security.policy.PolicyByUsername;
import org.hamcrest.Description;
import org.hamcrest.MatcherAssert;
import org.hamcrest.TypeSafeMatcher;
import org.hamcrest.core.AllOf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.Optional;

/**
 * Tests for {@link NuGet}.
 * Package metadata resource.
 */
class NuGetPackageMetadataTest {

    private NuGet nuget;

    private InMemoryStorage storage;

    @BeforeEach
    void init() throws Exception {
        this.storage = new InMemoryStorage();
        this.nuget = new NuGet(
            URI.create("http://localhost:4321/repo").toURL(),
            new AstoRepository(this.storage),
            new PolicyByUsername(TestAuthentication.USERNAME),
            new TestAuthentication(),
            "test",
            Optional.empty()
        );
    }

    @Test
    void shouldGetRegistration() {
        new Versions()
            .add(new Version("12.0.3"))
            .save(
                this.storage,
                new PackageKeys("Newtonsoft.Json").versionsKey()
            );
        final Nuspec.Xml nuspec = new Nuspec.Xml(
            String.join(
                "",
                "<?xml version=\"1.0\"?>",
                "<package xmlns=\"http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd\">",
                "<metadata><id>Newtonsoft.Json</id><version>12.0.3</version></metadata>",
                "</package>"
            ).getBytes()
        );
        this.storage.save(
            new PackageIdentity(nuspec.id(), nuspec.version()).nuspecKey(),
            new Content.From(nuspec.bytes())
        ).join();
        final Response response = this.nuget.response(
            new RequestLine(
                RqMethod.GET,
                "/registrations/newtonsoft.json/index.json"
            ),
            TestAuthentication.HEADERS,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            response,
            new AllOf<>(
                Arrays.asList(
                    new RsHasStatus(RsStatus.OK),
                    new RsHasBody(new IsValidRegistration())
                )
            )
        );
    }

    @Test
    void shouldGetRegistrationsWhenEmpty() {
        final Response response = this.nuget.response(
            new RequestLine(
                RqMethod.GET,
                "/registrations/my.lib/index.json"
            ),
            TestAuthentication.HEADERS,
            Content.EMPTY
        ).join();
        Assertions.assertEquals(RsStatus.OK, response.status());
        MatcherAssert.assertThat(
            response, new RsHasBody(new IsValidRegistration())
        );
    }

    @Test
    void shouldFailPutRegistration() {
        final Response response = this.nuget.response(
            new RequestLine(
                RqMethod.PUT,
                "/registrations/newtonsoft.json/index.json"
            ),
            TestAuthentication.HEADERS,
            Content.EMPTY
        ).join();
        Assertions.assertEquals(RsStatus.METHOD_NOT_ALLOWED, response.status());
    }

    @Test
    void shouldUnauthorizedGetRegistrationForAnonymousUser() {
        final Response response = this.nuget.response(
            new RequestLine(
                RqMethod.GET,
                "/registrations/my-utils/index.json"
            ), Headers.EMPTY, Content.EMPTY
        ).join();
        Assertions.assertEquals(RsStatus.UNAUTHORIZED, response.status());
        Assertions.assertTrue(
            response.headers().stream()
                .anyMatch(header ->
                    header.getKey().equalsIgnoreCase("WWW-Authenticate")
                        && header.getValue().contains("Basic realm=\"pantera\"")
                )
        );
    }

    /**
     * Matcher for bytes array representing valid Registration JSON.
     *
     * @since 0.1
     */
    private static class IsValidRegistration extends TypeSafeMatcher<byte[]> {

        @Override
        public void describeTo(final Description description) {
            description.appendText("is registration JSON");
        }

        @Override
        public boolean matchesSafely(final byte[] bytes) {
            final JsonObject root;
            try (JsonReader reader = Json.createReader(new ByteArrayInputStream(bytes))) {
                root = reader.readObject();
            }
            return root.getInt("count") == root.getJsonArray("items").size();
        }
    }
}
