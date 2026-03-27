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
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.streams.ContentAsStream;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.pypi.NormalizedProjectName;
import com.auto1.pantera.pypi.meta.Metadata;
import com.auto1.pantera.pypi.meta.PackageInfo;
import com.jcabi.xml.XMLDocument;
import org.reactivestreams.Publisher;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Search slice.
 */
public final class SearchSlice implements Slice {

    private final Storage storage;

    public SearchSlice(final Storage storage) {
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        return new NameFromXml(body).get().thenCompose(
            name -> {
                final Key.From key = new Key.From(
                    new NormalizedProjectName.Simple(name).value()
                );
                return this.storage.list(key).thenCompose(
                    list -> {
                        CompletableFuture<Content> res = new CompletableFuture<>();
                        if (list.isEmpty()) {
                            res.complete(new Content.From(SearchSlice.empty()));
                        } else {
                            final Key latest = list.stream().map(Key::string)
                                .max(Comparator.naturalOrder())
                                .map(Key.From::new)
                                .orElseThrow(IllegalStateException::new);
                            res = this.storage.value(latest).thenCompose(
                                val -> new ContentAsStream<PackageInfo>(val).process(
                                    input ->
                                        new Metadata.FromArchive(input, latest.string()).read()
                                )
                            ).thenApply(info -> new Content.From(SearchSlice.found(info)));
                        }
                        return res;
                    }
                );
            }
        ).handle(
            (content, throwable) -> {
                if (throwable == null) {
                    return ResponseBuilder.ok()
                        .header("content-type", "text/xml")
                        .body(content)
                        .build();
                }
                return ResponseBuilder.internalError(throwable).build();
            }
        ).toCompletableFuture();
    }

    /**
     * Response body when no packages found by given name.
     * @return Xml string
     */
    static byte[] empty() {
        return String.join(
            "\n", "<methodResponse>",
            "<params>",
            "<param>",
            "<value><array><data>",
            "</data></array></value>",
            "</param>",
            "</params>",
            "</methodResponse>"
        ).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Response body xml for search result.
     * @param info Package info
     * @return Xml string
     */
    static byte[] found(final PackageInfo info) {
        return String.join(
            "\n",
            "<?xml version='1.0'?>",
            "<methodResponse>",
            "<params>",
            "<param>",
            "<value><array><data>",
            "<value><struct>",
            "<member>",
            "<name>name</name>",
            String.format("<value><string>%s</string></value>", info.name()),
            "</member>",
            "<member>",
            "<name>summary</name>",
            String.format("<value><string>%s</string></value>", info.summary()),
            "</member>",
            "<member>",
            "<name>version</name>",
            String.format("<value><string>%s</string></value>", info.version()),
            "</member>",
            "<member>",
            "<name>_pypi_ordering</name>",
            "<value><boolean>0</boolean></value>",
            "</member>",
            "</struct></value>",
            "</data></array></value>",
            "</param>",
            "</params>",
            "</methodResponse>"
        ).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Python project name from request body xml.
     * @since 0.7
     */
    static final class NameFromXml {

        /**
         * Xml body.
         */
        private final Publisher<ByteBuffer> body;

        /**
         * Ctor.
         * @param body Body
         */
        NameFromXml(final Publisher<ByteBuffer> body) {
            this.body = body;
        }

        /**
         * Obtain project name to from xml.
         * @return Name of the project
         */
        CompletionStage<String> get() {
            final String query = "//member/value/array/data/value/string/text()";
            return new Content.From(this.body).asStringFuture().thenApply(
                xml -> new XMLDocument(xml)
                    .nodes("/*[local-name()='methodCall']/*[local-name()='params']/*[local-name()='param']/*[local-name()='value']/*[local-name()='struct']/*[local-name()='member']")
            ).thenApply(
                nodes -> nodes.stream()
                    .filter(
                        node -> "name".equals(node.xpath("//member/name/text()").get(0))
                            && !node.xpath(query).isEmpty()
                    )
                    .findFirst()
                    .map(node -> node.xpath(query))
                    .map(node -> node.get(0))
                    .orElseThrow(
                        () -> new IllegalArgumentException("Invalid xml, project name not found")
                    )
            );
        }
    }
}
