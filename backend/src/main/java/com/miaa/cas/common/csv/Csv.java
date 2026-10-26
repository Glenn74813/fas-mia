package com.miaa.cas.common.csv;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal RFC 4180 reading for the CAS upload templates: UTF-8, optional BOM, quoted fields and doubled quotes. */
public final class Csv {

    private Csv() {
    }

    /** Non-blank lines of the file, BOM removed. */
    public static List<String> lines(byte[] content) {
        return new String(content, StandardCharsets.UTF_8).replace("﻿", "").lines().filter(l -> !l.isBlank()).toList();
    }

    public static List<String> split(String line) {
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
}
