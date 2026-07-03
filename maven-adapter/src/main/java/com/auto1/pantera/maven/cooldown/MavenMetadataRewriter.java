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
package com.auto1.pantera.maven.cooldown;

import com.auto1.pantera.cooldown.metadata.MetadataRewriteException;
import com.auto1.pantera.cooldown.metadata.MetadataRewriter;
import org.w3c.dom.Document;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayOutputStream;

/**
 * Maven metadata rewriter implementing cooldown SPI.
 * Serializes a filtered DOM {@link Document} back to XML bytes
 * via {@link javax.xml.transform.Transformer}.
 *
 * @since 2.2.0
 */
public final class MavenMetadataRewriter implements MetadataRewriter<Document> {

    /**
     * Content type for Maven metadata.
     */
    private static final String CONTENT_TYPE = "application/xml";

    @Override
    public byte[] rewrite(final Document metadata) {
        try {
            final TransformerFactory factory = TransformerFactory.newInstance();
            final Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformer.transform(
                new DOMSource(metadata), new StreamResult(out)
            );
            return out.toByteArray();
        } catch (final TransformerException ex) {
            throw new MetadataRewriteException(
                "Failed to serialize Maven metadata to XML", ex
            );
        }
    }

    @Override
    public String contentType() {
        return CONTENT_TYPE;
    }
}
