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
package com.auto1.pantera.http.log;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Header;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Sanitizes sensitive information from logs (headers, URLs, etc.).
 * Masks authorization tokens, API keys, passwords, and other credentials.
 * 
 * @since 1.18.15
 */
public final class LogSanitizer {

    /**
     * Sensitive header names that should be masked.
     */
    private static final List<String> SENSITIVE_HEADERS = List.of(
        "authorization",
        "x-api-key",
        "x-auth-token",
        "x-access-token",
        "cookie",
        "set-cookie",
        "proxy-authorization",
        "www-authenticate",
        "proxy-authenticate",
        "x-csrf-token",
        "x-xsrf-token"
    );

    /**
     * Pattern for Bearer tokens in Authorization header.
     */
    private static final Pattern BEARER_PATTERN = Pattern.compile(
        "(Bearer\\s+)[A-Za-z0-9\\-._~+/]+=*",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Pattern for Basic auth in Authorization header.
     */
    private static final Pattern BASIC_PATTERN = Pattern.compile(
        "(Basic\\s+)[A-Za-z0-9+/]+=*",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Pattern for credentials embedded in URL query strings or fragments.
     * Covers the common parameter names that show up in OAuth flows,
     * webhook signing, and badly-designed APIs that put secrets in the URL.
     */
    private static final Pattern URL_API_KEY_PATTERN = Pattern.compile(
        "([?&#](?:api[_-]?key|token|access[_-]?token|auth[_-]?token"
            + "|password|passwd|pwd|secret|client[_-]?secret|private[_-]?key"
            + "|signature|sig|sas|refresh[_-]?token|id[_-]?token)=)[^&\\s#]+",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Path segments that carry a credential by protocol: npm logout
     * ({@code /-/user/token/<token>}) and the npm tokens API
     * ({@code /-/npm/v1/tokens/token/<key>}).
     */
    private static final Pattern PATH_TOKEN_PATTERN = Pattern.compile(
        "(/-/user/token/|/-/npm/v1/tokens/token/)[^/?#\\s]+"
    );

    /**
     * Conda token-in-path ({@code /t/<token>/...}). Only segments that look like
     * a credential (16+ token characters) are masked so ordinary short
     * {@code /t/} directories in other formats stay readable.
     */
    private static final Pattern CONDA_TOKEN_PATTERN = Pattern.compile(
        "(/t/)[A-Za-z0-9\\-._~+=%]{16,}(?=[/?#\\s]|$)"
    );

    /**
     * A JWT anywhere in the text (header.payload.signature; header and
     * payload are base64url JSON objects, so they start with {@code eyJ}).
     */
    private static final Pattern JWT_PATTERN = Pattern.compile(
        "eyJ[A-Za-z0-9_-]{4,}\\.eyJ[A-Za-z0-9_-]{2,}\\.[A-Za-z0-9_-]*"
    );

    /**
     * Userinfo in an absolute URL ({@code scheme://user:pass@host}).
     */
    private static final Pattern USERINFO_PATTERN = Pattern.compile(
        "([A-Za-z][A-Za-z0-9+.-]*://)[^/@\\s?#]+@"
    );

    /**
     * Mask to use for sensitive data.
     */
    private static final String MASK = "***REDACTED***";

    /**
     * Private constructor - utility class.
     */
    private LogSanitizer() {
    }

    /**
     * Sanitize HTTP headers for logging.
     * Masks sensitive header values while preserving structure.
     * 
     * @param headers Original headers
     * @return Sanitized headers safe for logging
     */
    public static Headers sanitizeHeaders(final Headers headers) {
        final List<Header> sanitized = new ArrayList<>();
        for (final Header header : headers) {
            final String name = header.getKey();
            final String value = header.getValue();
            
            if (isSensitiveHeader(name)) {
                sanitized.add(new Header(name, maskValue(value)));
            } else {
                sanitized.add(header);
            }
        }
        return new Headers(sanitized);
    }

    /**
     * Sanitize a URL for logging by masking query parameters with sensitive names
     * and {@code /t/<token>/} path credentials.
     * 
     * @param url Original URL
     * @return Sanitized URL safe for logging
     */
    public static String sanitizeUrl(final String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        return redactCredentials(
            URL_API_KEY_PATTERN.matcher(url).replaceAll("$1" + MASK)
        );
    }

    /**
     * Sanitize an authorization header value specifically.
     * Handles Bearer, Basic, and other auth schemes.
     * 
     * @param authValue Authorization header value
     * @return Sanitized value showing only auth type
     */
    public static String sanitizeAuthHeader(final String authValue) {
        if (authValue == null || authValue.isEmpty()) {
            return authValue;
        }
        
        String result = authValue;
        
        // Mask Bearer tokens
        result = BEARER_PATTERN.matcher(result).replaceAll("$1" + MASK);
        
        // Mask Basic auth
        result = BASIC_PATTERN.matcher(result).replaceAll("$1" + MASK);
        
        // If no pattern matched but it looks like auth, mask everything after first space
        if (result.equals(authValue) && authValue.contains(" ")) {
            final int spaceIdx = authValue.indexOf(' ');
            result = authValue.substring(0, spaceIdx + 1) + MASK;
        }
        
        return result;
    }

    /**
     * Sanitize a generic string that might contain sensitive data.
     * Useful for error messages, log messages, etc.
     * 
     * @param message Original message
     * @return Sanitized message
     */
    public static String sanitizeMessage(final String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        
        String result = message;
        
        // Mask Bearer tokens
        result = BEARER_PATTERN.matcher(result).replaceAll("$1" + MASK);
        
        // Mask Basic auth
        result = BASIC_PATTERN.matcher(result).replaceAll("$1" + MASK);
        
        // Mask API keys in text
        result = result.replaceAll(
            "(?i)(api[_-]?key|token|password|secret)[\"']?\\s*[:=]\\s*[\"']?[A-Za-z0-9\\-._~+/]+",
            "$1=" + MASK
        );

        return redactCredentials(result);
    }

    /**
     * Mask credentials that a URL may carry (JWTs, token path segments,
     * userinfo) inside free text such as a log message or an exception
     * message. Unlike {@link #sanitizeMessage(String)} it does not rewrite
     * {@code key=value} prose, so ordinary diagnostics stay intact.
     *
     * @param text Original text, may be null
     * @return Text safe for logging
     */
    public static String sanitizeText(final String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return redactCredentials(text);
    }

    /**
     * Mask credentials carried in URL paths, userinfo and JWTs anywhere in the
     * text. The cheap {@code contains} guards keep the common case (no
     * credential) free of regex work.
     *
     * @param text Text to redact
     * @return Redacted text
     */
    private static String redactCredentials(final String text) {
        String result = text;
        if (result.contains("eyJ")) {
            result = JWT_PATTERN.matcher(result).replaceAll(MASK);
        }
        if (result.contains("/-/")) {
            result = PATH_TOKEN_PATTERN.matcher(result).replaceAll("$1" + MASK);
        }
        if (result.contains("/t/")) {
            result = CONDA_TOKEN_PATTERN.matcher(result).replaceAll("$1" + MASK);
        }
        if (result.indexOf('@') >= 0 && result.contains("://")) {
            result = USERINFO_PATTERN.matcher(result).replaceAll("$1" + MASK + "@");
        }
        return result;
    }

    /**
     * Check if a header name is sensitive and should be masked.
     * 
     * @param headerName Header name to check
     * @return True if header is sensitive
     */
    private static boolean isSensitiveHeader(final String headerName) {
        final String lower = headerName.toLowerCase(Locale.US);
        return SENSITIVE_HEADERS.stream().anyMatch(lower::equals);
    }

    /**
     * Mask a header value, showing only type/prefix if applicable.
     * 
     * @param value Original value
     * @return Masked value
     */
    private static String maskValue(final String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        
        // For auth headers, preserve the auth type
        if (value.toLowerCase(Locale.US).startsWith("bearer ")) {
            return "Bearer " + MASK;
        }
        if (value.toLowerCase(Locale.US).startsWith("basic ")) {
            return "Basic " + MASK;
        }
        
        // For other sensitive values, mask completely
        return MASK;
    }
}
