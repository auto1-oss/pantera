/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.http.auth;

import com.artipie.asto.Content;
import com.artipie.asto.memory.InMemoryStorage;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.RsStatus;
import com.artipie.http.rq.RequestLine;
import com.artipie.npm.model.User;
import com.artipie.npm.repository.StorageTokenRepository;
import com.artipie.npm.repository.StorageUserRepository;
import com.artipie.npm.security.BCryptPasswordHasher;
import com.artipie.npm.security.TokenGenerator;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import javax.json.Json;

/**
 * Test for {@link AddUserSlice}.
 *
 * @since 1.1
 */
final class AddUserSliceTest {
    
    /**
     * Test slice.
     */
    private AddUserSlice slice;
    
    /**
     * User repository.
     */
    private StorageUserRepository users;
    
    @BeforeEach
    void setUp() {
        final InMemoryStorage storage = new InMemoryStorage();
        this.users = new StorageUserRepository(storage, new BCryptPasswordHasher());
        final StorageTokenRepository tokens = new StorageTokenRepository(storage);
        this.slice = new AddUserSlice(
            this.users,
            tokens,
            new BCryptPasswordHasher(),
            new TokenGenerator()
        );
    }
    
    @Test
    void createsNewUser() {
        // Given
        final String body = Json.createObjectBuilder()
            .add("_id", "org.couchdb.user:alice")
            .add("name", "alice")
            .add("password", "secret123")
            .add("email", "alice@example.com")
            .add("type", "user")
            .add("date", "2025-10-22T18:00:00.000Z")
            .build().toString();
        
        // When
        final Response response = this.slice.response(
            RequestLine.from("PUT /-/user/org.couchdb.user:alice HTTP/1.1"),
            Headers.EMPTY,
            new Content.From(body.getBytes(StandardCharsets.UTF_8))
        ).join();
        
        // Then
        MatcherAssert.assertThat(
            "Response status is CREATED",
            response.status(),
            new IsEqual<>(RsStatus.CREATED)
        );
        
        // Verify user was saved
        MatcherAssert.assertThat(
            "User exists in repository",
            this.users.exists("alice").join(),
            new IsEqual<>(true)
        );
    }
    
    @Test
    void rejectsExistingUser() {
        // Given: user already exists
        final String password = new BCryptPasswordHasher().hash("password");
        this.users.save(new User("bob", password, "bob@example.com")).join();
        
        final String body = Json.createObjectBuilder()
            .add("name", "bob")
            .add("password", "newpassword")
            .add("email", "bob2@example.com")
            .build().toString();
        
        // When
        final Response response = this.slice.response(
            RequestLine.from("PUT /-/user/org.couchdb.user:bob HTTP/1.1"),
            Headers.EMPTY,
            new Content.From(body.getBytes(StandardCharsets.UTF_8))
        ).join();
        
        // Then
        MatcherAssert.assertThat(
            "Response status is BAD_REQUEST (user exists)",
            response.status(),
            new IsEqual<>(RsStatus.BAD_REQUEST)
        );
    }
    
    @Test
    void requiresPassword() {
        // Given: no password in body
        final String body = Json.createObjectBuilder()
            .add("name", "charlie")
            .add("email", "charlie@example.com")
            .build().toString();
        
        // When
        final Response response = this.slice.response(
            RequestLine.from("PUT /-/user/org.couchdb.user:charlie HTTP/1.1"),
            Headers.EMPTY,
            new Content.From(body.getBytes(StandardCharsets.UTF_8))
        ).join();
        
        // Then
        MatcherAssert.assertThat(
            "Response status is INTERNAL_ERROR",
            response.status(),
            new IsEqual<>(RsStatus.INTERNAL_ERROR)
        );
    }
    
    @Test
    void rejectsBadPath() {
        // Given: invalid path
        final String body = Json.createObjectBuilder()
            .add("name", "alice")
            .add("password", "secret")
            .add("email", "alice@example.com")
            .build().toString();
        
        // When
        final Response response = this.slice.response(
            RequestLine.from("PUT /-/user/alice HTTP/1.1"),
            Headers.EMPTY,
            new Content.From(body.getBytes(StandardCharsets.UTF_8))
        ).join();
        
        // Then
        MatcherAssert.assertThat(
            "Response status is BAD_REQUEST",
            response.status(),
            new IsEqual<>(RsStatus.BAD_REQUEST)
        );
    }
}
