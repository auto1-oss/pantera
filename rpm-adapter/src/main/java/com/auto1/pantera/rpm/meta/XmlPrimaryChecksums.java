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
package com.auto1.pantera.rpm.meta;

import com.auto1.pantera.asto.PanteraIOException;
import com.auto1.pantera.asto.misc.UncheckedIOConsumer;
import com.fasterxml.aalto.stax.InputFactoryImpl;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;

/**
 * Extracts packages names and checksums from primary xml.
 * @since 0.8
 */
public final class XmlPrimaryChecksums {

    /**
     * Primary file path (nullable when constructed from an InputStream).
     */
    private final Path path;

    /**
     * Primary input stream (nullable when constructed from a Path – opened lazily in
     * {@link #read()}).
     */
    private final InputStream inp;

    /**
     * Ctor.
     * @param path Primary file path
     */
    public XmlPrimaryChecksums(final Path path) {
        this.path = path;
        this.inp = null;
    }

    /**
     * Ctor.
     * @param inp Primary input stream
     */
    public XmlPrimaryChecksums(final InputStream inp) {
        this.path = null;
        this.inp = inp;
    }

    /**
     * Reads xml.
     * @return Map of packages names and checksums.
     */
    public Map<String, String> read() {
        if (this.path != null) {
            try (InputStream stream = Files.newInputStream(this.path)) {
                return XmlPrimaryChecksums.parse(stream, false);
            } catch (final IOException err) {
                throw new PanteraIOException(err);
            }
        }
        return XmlPrimaryChecksums.parse(this.inp, true);
    }

    /**
     * Parse the primary xml from the given stream.
     * @param stream Input stream to read from
     * @param closeStream Whether to close the stream after parsing (legacy behaviour
     *  for the {@code InputStream} ctor – the caller that owns the stream via
     *  {@code Path} ctor relies on try-with-resources instead).
     * @return Map of packages names and checksums.
     */
    private static Map<String, String> parse(final InputStream stream, final boolean closeStream) {
        final Map<String, String> res = new HashMap<>();
        try {
            final XMLEventReader reader = new InputFactoryImpl().createXMLEventReader(stream);
            XMLEvent event;
            String name = "";
            String checksum = "";
            while (reader.hasNext()) {
                event = reader.nextEvent();
                if (XmlPrimaryChecksums.isTag(event, "location")) {
                    name = event.asStartElement().getAttributeByName(new QName("href")).getValue();
                }
                if (XmlPrimaryChecksums.isTag(event, "checksum")) {
                    event = reader.nextEvent();
                    checksum = event.asCharacters().getData();
                }
                if (event.isEndElement()
                    && "package".equals(event.asEndElement().getName().getLocalPart())) {
                    res.put(name, checksum);
                }
            }
            reader.close();
        } catch (final XMLStreamException err) {
            throw new PanteraIOException(err);
        } finally {
            if (closeStream) {
                Optional.of(stream).ifPresent(new UncheckedIOConsumer<>(InputStream::close));
            }
        }
        return res;
    }

    /**
     * Checks event.
     * @param event Event
     * @param tag Xml tag name
     * @return True is this event is xml tag with given tag name
     */
    private static boolean isTag(final XMLEvent event, final String tag) {
        return event.isStartElement()
            && event.asStartElement().getName().getLocalPart().equals(tag);
    }
}