/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.conda.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.auth.Tokens;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.hm.RsHasBody;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import org.apache.commons.lang3.NotImplementedException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link GenerateTokenSlice}.
 */
class GenerateTokenSliceTest {

    /**
     * Test token.
     */
    private static final String TOKEN = "abc123";

    /**
     * Anonymous token.
     */
    private static final String ANONYMOUS_TOKEN = "anonymous123";

    @Test
    void addsToken() {
        final String name = "Alice";
        final String pswd = "wonderland";
        MatcherAssert.assertThat(
            "Slice response in not 200 OK",
            new GenerateTokenSlice(
                new Authentication.Single(name, pswd),
                new FakeAuthTokens()
            ),
            new SliceHasResponse(
                Matchers.allOf(
                    new RsHasStatus(RsStatus.OK),
                    new RsHasBody(
                        String.format("{\"token\":\"%s\"}", GenerateTokenSliceTest.TOKEN).getBytes()
                    )
                ),
                new RequestLine(RqMethod.POST, "/authentications"),
                Headers.from(new Authorization.Basic(name, pswd)),
                Content.EMPTY
            )
        );
    }

    @Test
    void returnsUnauthorized() {
        MatcherAssert.assertThat(
            new GenerateTokenSlice(
                new Authentication.Single("Jora", "123"),
                new FakeAuthTokens()
            ),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.UNAUTHORIZED),
                new RequestLine(RqMethod.POST, "/any/line"),
                Headers.from(new Authorization.Basic("Jora", "0987")),
                Content.EMPTY
            )
        );
    }

    @Test
    void anonymousToken() {
        MatcherAssert.assertThat(
            "Slice response in not 200 OK",
            new GenerateTokenSlice(
                new Authentication.Single("test_user", "aaa"),
                new FakeAuthTokens()
            ),
            new SliceHasResponse(
                Matchers.allOf(
                    new RsHasStatus(RsStatus.OK),
                    new RsHasBody(
                        String.format("{\"token\":\"%s\"}", GenerateTokenSliceTest.ANONYMOUS_TOKEN)
                            .getBytes()
                    )
                ),
                new RequestLine(RqMethod.POST, "/authentications")
            )
        );
    }

    /**
     * Fake implementation of {@link Tokens}.
     * @since 0.5
     */
    static class FakeAuthTokens implements Tokens {

        @Override
        public TokenAuthentication auth() {
            throw new NotImplementedException("Not implemented");
        }

        @Override
        public String generate(final AuthUser user) {
            return user.isAnonymous()
                ? GenerateTokenSliceTest.ANONYMOUS_TOKEN
                : GenerateTokenSliceTest.TOKEN;
        }
    }

}
