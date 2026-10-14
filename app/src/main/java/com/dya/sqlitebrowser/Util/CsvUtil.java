package com.dya.sqlitebrowser.Util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

public class CsvUtil {

    /** Build one CSV line (without the line break) */
    public static String line(String[] fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(escape(fields[i]));
        }
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /** Parse CSV text (comma separated, quotes supported, multi-line cells supported) */
    public static List<String[]> parse(Reader reader) throws IOException {
        List<String[]> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        boolean any = false;
        boolean first = true;

        BufferedReader in = new BufferedReader(reader);
        int ch;
        while ((ch = in.read()) != -1) {
            char c = (char) ch;
            if (first) {
                first = false;
                if (c == '\uFEFF') continue; // skip BOM
            }
            if (inQuotes) {
                if (c == '"') {
                    in.mark(1);
                    int nx = in.read();
                    if (nx == '"') {
                        sb.append('"');
                    } else {
                        inQuotes = false;
                        if (nx != -1) in.reset();
                    }
                } else {
                    sb.append(c);
                }
            } else if (c == '"' && sb.length() == 0) {
                inQuotes = true;
                any = true;
            } else if (c == ',') {
                row.add(sb.toString());
                sb.setLength(0);
                any = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r') {
                    in.mark(1);
                    int nx = in.read();
                    if (nx != '\n' && nx != -1) in.reset();
                }
                if (any || sb.length() > 0) {
                    row.add(sb.toString());
                    rows.add(row.toArray(new String[0]));
                }
                row.clear();
                sb.setLength(0);
                any = false;
            } else {
                sb.append(c);
            }
        }
        if (any || sb.length() > 0) {
            row.add(sb.toString());
            rows.add(row.toArray(new String[0]));
        }
        return rows;
    }
}