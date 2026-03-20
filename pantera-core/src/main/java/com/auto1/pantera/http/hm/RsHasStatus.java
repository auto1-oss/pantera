/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */

package com.auto1.pantera.http.hm;

import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.hamcrest.core.IsEqual;

/**
 * Matcher to verify response status.
 */
public final class RsHasStatus extends TypeSafeMatcher<Response> {

    /**
     * Status code matcher.
     */
    private final Matcher<RsStatus> status;

    /**
     * @param status Code to match
     */
    public RsHasStatus(final RsStatus status) {
        this(new IsEqual<>(status));
    }

    /**
     * @param status Code matcher
     */
    public RsHasStatus(final Matcher<RsStatus> status) {
        this.status = status;
    }

    @Override
    public void describeTo(final Description description) {
        description.appendDescriptionOf(this.status);
    }

    @Override
    public boolean matchesSafely(final Response item) {
        return this.status.matches(item.status());
    }
}
