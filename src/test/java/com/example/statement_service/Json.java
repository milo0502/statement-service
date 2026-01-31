package com.example.statement_service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Json {
    private Json() {}

    static String extract(String json, String field) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(json);
        if (!m.find()) throw new IllegalArgumentException("Field not found: " + field);
        return m.group(1);
    }
}