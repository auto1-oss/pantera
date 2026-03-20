/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
     * Pattern for API keys in URLs.
     */
    private static final Pattern URL_API_KEY_PATTERN = Pattern.compile(
        "([?&](?:api[_-]?key|token|access[_-]?token|auth[_-]?token)=)[^&\\s]+",
        Pattern.CASE_INSENSITIVE
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
     * Sanitize a URL for logging by masking query parameters with sensitive names.
     * 
     * @param url Original URL
     * @return Sanitized URL safe for logging
     */
    public static String sanitizeUrl(final String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        return URL_API_KEY_PATTERN.matcher(url).replaceAll("$1" + MASK);
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
