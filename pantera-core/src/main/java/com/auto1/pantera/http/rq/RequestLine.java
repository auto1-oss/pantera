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
package com.auto1.pantera.http.rq;

import java.net.URI;
import java.util.Objects;

/**
 * Request line helper object.
 * <p>
 * See 5.1 section of RFC2616:
 * <p>
 * The Request-Line begins with a method token,
 * followed by the Request-URI and the protocol version,
 * and ending with {@code CRLF}.
 * The elements are separated by SP characters.
 * No {@code CR} or {@code LF} is allowed except in the final {@code CRLF} sequence.
 * <p>
 *     {@code Request-Line = Method SP Request-URI SP HTTP-Version CRLF}.
 * @see <a href="https://www.w3.org/Protocols/rfc2616/rfc2616-sec5.html">RFC2616</a>
 */
public final class RequestLine {

    public static RequestLine from(String line) {
        RequestLineFrom from = new RequestLineFrom(line);
        return new RequestLine(from.method(), from.uri(), from.version());
    }

    /**
     * The request method.
     */
    private final RqMethod method;

    /**
     * The request uri.
     */
    private final URI uri;

    /**
     * The Http version.
     */
    private final String version;

    /**
     * @param method Request method.
     * @param uri Request URI.
     */
    public RequestLine(RqMethod method, String uri) {
        this(method.value(), uri);
    }

    /**
     * @param method Request method.
     * @param uri Request URI.
     */
    public RequestLine(String method, String uri) {
        this(method, uri, "HTTP/1.1");
    }

    /**
     * @param method The http method.
     * @param uri The http uri.
     * @param version The http version.
     */
    public RequestLine(String method, String uri, String version) {
        this(RqMethod.valueOf(method.toUpperCase()), URI.create(sanitizeUri(uri)), version);
    }
    
    /**
     * Sanitize URI by encoding illegal characters.
     * Handles cases like Maven artifacts with unresolved properties: ${version}
     * @param uri Raw URI string
     * @return Sanitized URI string safe for URI.create()
     */
    private static String sanitizeUri(String uri) {
        // Handle empty or malformed URIs
        if (uri == null || uri.isEmpty()) {
            return "/";
        }
        
        // Handle "//" which URI parser interprets as start of authority (hostname)
        // but with no actual authority, causing "Expected authority" error
        if ("//".equals(uri)) {
            return "/";
        }
        
        // Replace illegal characters that commonly appear in badly-formed requests
        // $ is illegal in URI paths without encoding
        // Maven properties like ${commons-support.version} should be %24%7B...%7D
        // Maven version ranges like [release] should be %5B...%5D
        return uri
            .replace("$", "%24")  // $ → %24
            .replace("{", "%7B")  // { → %7B
            .replace("}", "%7D")  // } → %7D
            .replace("[", "%5B")  // [ → %5B (Maven version ranges)
            .replace("]", "%5D")  // ] → %5D (Maven version ranges)
            .replace("|", "%7C")  // | → %7C (also commonly problematic)
            .replace("\\", "%5C"); // \ → %5C (Windows paths)
    }

    public RequestLine(RqMethod method, URI uri, String version) {
        this.method = method;
        this.uri = uri;
        this.version = version;
    }

    public RqMethod method() {
        return method;
    }

    public URI uri() {
        return uri;
    }

    public String version() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RequestLine that = (RequestLine) o;
        return method == that.method && Objects.equals(uri, that.uri) && Objects.equals(version, that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, uri, version);
    }

    @Override
    public String toString() {
        return this.method.value() + ' ' + this.uri + ' ' + this.version;
    }
}
