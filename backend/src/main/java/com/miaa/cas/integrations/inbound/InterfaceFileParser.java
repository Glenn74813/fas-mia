package com.miaa.cas.integrations.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Reads an interface file into header fields and rows, whatever its format. Field names are compared without case,
 * spaces, dashes or underscores ({@code account_code}, {@code accountCode} and {@code Account Code} are the same).
 * <ul>
 * <li>CSV: a header row, then one row per line; header fields come from the request.</li>
 * <li>JSON: an object whose plain values are header fields and whose array ({@code lines}, {@code rows} or
 * {@code items}) holds the rows.</li>
 * <li>XML: the root element's attributes and its plain text children are header fields; children that have attributes
 * or children of their own (directly, or inside {@code <lines>}) are the rows. DOCTYPEs and external entities are
 * refused.</li>
 * </ul>
 */
final class InterfaceFileParser {

    static final int MAX_ROWS = 5000;

    record Parsed(Map<String, String> header, List<Map<String, String>> rows) {
    }

    static final class ParseException extends Exception {
        private static final long serialVersionUID = 1L;

        ParseException(String message) {
            super(message);
        }
    }

    private InterfaceFileParser() {
    }

    static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    static Parsed parse(String format, byte[] content, Map<String, String> requestHeader, ObjectMapper json) throws ParseException {
        String text = utf8(content);
        Parsed p = switch (format) {
            case "CSV" -> csv(text);
            case "JSON" -> json(text, json);
            case "XML" -> xml(content);
            default -> throw new ParseException("Unsupported format " + format + ".");
        };
        Map<String, String> header = new LinkedHashMap<>(p.header());
        requestHeader.forEach((k, v) -> {
            if (v != null && !v.isBlank()) {
                header.putIfAbsent(key(k), v.trim());
            }
        });
        if (p.rows().isEmpty()) {
            throw new ParseException("The file has no rows.");
        }
        if (p.rows().size() > MAX_ROWS) {
            throw new ParseException("The file has more than " + MAX_ROWS + " rows; split it.");
        }
        return new Parsed(header, p.rows());
    }

    private static String utf8(byte[] content) throws ParseException {
        try {
            String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(content)).toString();
            return s.startsWith("﻿") ? s.substring(1) : s;
        } catch (CharacterCodingException e) {
            throw new ParseException("The file is not UTF-8 text.");
        }
    }

    // ---------------------------------------------------------------- CSV (RFC 4180)

    private static Parsed csv(String text) throws ParseException {
        List<List<String>> records = csvRecords(text);
        if (records.isEmpty()) {
            throw new ParseException("The file is empty.");
        }
        List<String> names = records.get(0).stream().map(InterfaceFileParser::key).toList();
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> r = records.get(i);
            if (r.size() == 1 && r.get(0).isBlank()) {
                continue;
            }
            if (r.size() != names.size()) {
                throw new ParseException("Line " + (i + 1) + " has " + r.size() + " fields; the header has " + names.size() + ".");
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < names.size(); c++) {
                row.put(names.get(c), r.get(c).trim());
            }
            rows.add(row);
        }
        return new Parsed(Map.of(), rows);
    }

    static List<List<String>> csvRecords(String text) throws ParseException {
        List<List<String>> out = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            any = true;
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"' && field.isEmpty()) {
                quoted = true;
            } else if (ch == ',') {
                record.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                record.add(field.toString());
                field.setLength(0);
                out.add(record);
                record = new ArrayList<>();
                any = false;
            } else {
                field.append(ch);
            }
        }
        if (quoted) {
            throw new ParseException("A quoted field is not closed.");
        }
        if (any || !record.isEmpty()) {
            record.add(field.toString());
            out.add(record);
        }
        return out;
    }

    // ---------------------------------------------------------------- JSON

    private static Parsed json(String text, ObjectMapper json) throws ParseException {
        JsonNode root;
        try {
            root = json.readTree(text);
        } catch (IOException e) {
            throw new ParseException("The file is not valid JSON.");
        }
        if (root == null || !root.isObject()) {
            throw new ParseException("The JSON must be an object with the header fields and a \"lines\" array.");
        }
        Map<String, String> header = new LinkedHashMap<>();
        JsonNode array = null;
        for (Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getValue().isArray() && List.of("lines", "rows", "items").contains(key(e.getKey()))) {
                array = e.getValue();
            } else if (e.getValue().isValueNode() && !e.getValue().isNull()) {
                header.put(key(e.getKey()), e.getValue().asText());
            }
        }
        if (array == null) {
            throw new ParseException("The JSON has no \"lines\" array.");
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (JsonNode item : array) {
            if (!item.isObject()) {
                throw new ParseException("Every line must be an object.");
            }
            Map<String, String> row = new LinkedHashMap<>();
            item.fields().forEachRemaining(f -> {
                if (f.getValue().isValueNode() && !f.getValue().isNull()) {
                    row.put(key(f.getKey()), f.getValue().asText().trim());
                }
            });
            rows.add(row);
        }
        return new Parsed(header, rows);
    }

    // ---------------------------------------------------------------- XML

    private static Parsed xml(byte[] content) throws ParseException {
        Element root;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setNamespaceAware(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setEntityResolver((publicId, systemId) -> new org.xml.sax.InputSource(new StringReader("")));
            b.setErrorHandler(null);
            root = b.parse(new ByteArrayInputStream(content)).getDocumentElement();
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new ParseException("The file is not valid XML (DOCTYPE declarations are not accepted).");
        }
        Map<String, String> header = attributes(root);
        List<Map<String, String>> rows = new ArrayList<>();
        for (Element c : elements(root)) {
            if (List.of("lines", "rows", "items").contains(key(c.getTagName()))) {
                for (Element r : elements(c)) {
                    rows.add(fields(r));
                }
            } else if (!elements(c).isEmpty() || c.getAttributes().getLength() > 0) {
                rows.add(fields(c));
            } else {
                header.put(key(c.getTagName()), c.getTextContent().trim());
            }
        }
        return new Parsed(header, rows);
    }

    private static Map<String, String> fields(Element e) {
        Map<String, String> row = attributes(e);
        for (Element c : elements(e)) {
            row.put(key(c.getTagName()), c.getTextContent().trim());
        }
        return row;
    }

    private static Map<String, String> attributes(Element e) {
        Map<String, String> m = new LinkedHashMap<>();
        NamedNodeMap a = e.getAttributes();
        for (int i = 0; i < a.getLength(); i++) {
            m.put(key(a.item(i).getNodeName()), a.item(i).getNodeValue().trim());
        }
        return m;
    }

    private static List<Element> elements(Element e) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = e.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE) {
                out.add((Element) nodes.item(i));
            }
        }
        return out;
    }
}
