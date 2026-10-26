package com.miaa.cas.migration;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reader for the CAS migration templates: UTF-8, a header row naming exactly the template's columns (in any
 * order), comma-separated, double quotes around values that contain commas. Lines starting with '#' and blank
 * lines are ignored. Legacy extracts are mapped onto these templates before upload (C-12).
 */
final class MigrationCsv {

    record Row(int rowNo, Map<String, String> values) {
    }

    private MigrationCsv() {
    }

    static List<Row> read(byte[] content, List<String> columns, int maxRows) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            throw invalid("The file is not UTF-8 text.");
        }
        List<String> lines = text.replace("﻿", "").lines().toList();
        List<String> header = null;
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            List<String> fields = split(line);
            if (header == null) {
                header = fields.stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).toList();
                if (header.size() != columns.size() || !header.containsAll(columns)) {
                    throw invalid("The header must be exactly: " + String.join(",", columns) + ".");
                }
                continue;
            }
            int rowNo = i + 1;
            if (fields.size() != header.size()) {
                throw invalid("Line " + rowNo + ": expected " + header.size() + " columns, found " + fields.size() + ".");
            }
            if (rows.size() == maxRows) {
                throw invalid("A batch holds at most " + maxRows + " rows; split the file.");
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (String c : columns) {
                values.put(c, fields.get(header.indexOf(c)).trim());
            }
            rows.add(new Row(rowNo, values));
        }
        if (header == null) {
            throw invalid("The file has no header row.");
        }
        if (rows.isEmpty()) {
            throw invalid("The file has no data rows.");
        }
        return rows;
    }

    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
