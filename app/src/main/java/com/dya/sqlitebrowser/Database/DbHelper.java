package com.dya.sqlitebrowser.Database;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DbHelper {

    public static final int MAX_ROWS = 1000;

    /** A WHERE clause + its arguments */
    public static class Filter {
        public String where = "";
        public String[] args = new String[0];

        public boolean active() {
            return !where.isEmpty();
        }
    }

    /** Quote an identifier (table / column name) */
    public static String q(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }

    /** Tables and views */
    public static List<String> getTables(SQLiteDatabase db) {
        return names(db, "type IN ('table','view')");
    }

    /** Tables only (no views) */
    public static List<String> getTablesOnly(SQLiteDatabase db) {
        return names(db, "type='table'");
    }

    private static List<String> names(SQLiteDatabase db, String cond) {
        List<String> list = new ArrayList<>();
        Cursor c = db.rawQuery("SELECT name FROM sqlite_master WHERE " + cond +
                " AND name NOT LIKE 'sqlite_%' ORDER BY name", null);
        while (c.moveToNext()) list.add(c.getString(0));
        c.close();
        return list;
    }

    public static List<String> getColumns(SQLiteDatabase db, String table) {
        List<String> list = new ArrayList<>();
        Cursor c = db.rawQuery("PRAGMA table_info(" + q(table) + ")", null);
        while (c.moveToNext()) list.add(c.getString(1));
        c.close();
        return list;
    }

    /** Each item: {index name, table name, sql} */
    public static List<String[]> getIndexes(SQLiteDatabase db) {
        List<String[]> list = new ArrayList<>();
        Cursor c = db.rawQuery("SELECT name, tbl_name, sql FROM sqlite_master " +
                "WHERE type='index' AND name NOT LIKE 'sqlite_%' ORDER BY tbl_name, name", null);
        while (c.moveToNext()) list.add(new String[]{c.getString(0), c.getString(1), c.getString(2)});
        c.close();
        return list;
    }

    /** Run a script with one or more statements. Returns the last result set (if any). */

    public static String getSchema(SQLiteDatabase db) {
        StringBuilder sb = new StringBuilder();
        Cursor c = db.rawQuery("SELECT type, name, sql FROM sqlite_master " +
                "WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name", null);
        while (c.moveToNext()) {
            sb.append("-- ").append(c.getString(0).toUpperCase()).append(": ")
                    .append(c.getString(1)).append("\n");
            String sql = c.getString(2);
            if (sql != null) sb.append(sql).append(";\n");
            sb.append("\n");
        }
        c.close();
        return sb.toString(); // empty string when the database is empty
    }


    // COLUMN KINDS

    public static final int KIND_TEXT = 0, KIND_INT = 1, KIND_REAL = 2, KIND_DIGITS = 3;

    /** Which keyboard a column needs, from its declared type (same idea as SQLite affinity) */
    public static int kindOf(String declaredType) {
        String t = declaredType == null ? "" : declaredType.toUpperCase(Locale.ROOT);
        // TEXT_NUM = stored as TEXT (keeps leading zeros like 07) but typed with a digits keyboard
        if (t.contains("TEXT_NUM")) return KIND_DIGITS;
        if (t.contains("INT")) return KIND_INT;
        if (t.contains("CHAR") || t.contains("CLOB") || t.contains("TEXT")) return KIND_TEXT;
        if (t.contains("REAL") || t.contains("FLOA") || t.contains("DOUB")
                || t.contains("NUMERIC") || t.contains("DECIMAL")) return KIND_REAL;
        return KIND_TEXT; // BLOB, DATE, BOOLEAN, no type, ... -> normal keyboard
    }

    /** KIND_* for each column in cols (same order). Unknown columns are KIND_TEXT. */
    public static int[] getKinds(SQLiteDatabase db, String table, String[] cols) {
        Map<String, Integer> m = new HashMap<>();
        Cursor c = db.rawQuery("PRAGMA table_info(" + q(table) + ")", null);
        while (c.moveToNext()) m.put(c.getString(1), kindOf(c.getString(2)));
        c.close();
        int[] k = new int[cols.length];
        for (int i = 0; i < cols.length; i++) {
            Integer v = m.get(cols[i]);
            k[i] = v == null ? KIND_TEXT : v;
        }
        return k;
    }

    /**
     * Numeric keyboards can type Arabic-Indic digits (٠١٢) or a comma/Arabic decimal mark
     * when the app language is Arabic or Kurdish. Convert them to plain 0-9 and '.'
     */
    public static String normalizeNumber(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.trim().toCharArray()) {
            if (ch >= '\u0660' && ch <= '\u0669') sb.append((char) ('0' + (ch - '\u0660')));
            else if (ch >= '\u06F0' && ch <= '\u06F9') sb.append((char) ('0' + (ch - '\u06F0')));
            else if (ch == '\u066B' || ch == ',' || ch == '\u060C') sb.append('.');
            else sb.append(ch);
        }
        return sb.toString();
    }

    // FILTER

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * op: 0 contains, 1 equals, 2 starts with, 3 ends with, 4 >, 5 <, 6 is NULL, 7 is not NULL
     * col == null means: any column
     */
    public static Filter buildFilter(List<String> allCols, String col, int op, String text) {
        Filter f = new Filter();
        if (text == null) text = "";
        if (op < 6 && text.isEmpty()) return f;

        List<String> targets = new ArrayList<>();
        if (col != null && allCols.contains(col)) targets.add(col);
        else targets.addAll(allCols);
        if (targets.isEmpty()) return f;

        StringBuilder sb = new StringBuilder();
        List<String> args = new ArrayList<>();
        for (String c : targets) {
            if (sb.length() > 0) sb.append(" OR ");
            String qc = q(c);
            switch (op) {
                case 0:
                    sb.append("CAST(").append(qc).append(" AS TEXT) LIKE ? ESCAPE '\\'");
                    args.add("%" + esc(text) + "%");
                    break;
                case 1:
                    sb.append(qc).append(" = ?");
                    args.add(text);
                    break;
                case 2:
                    sb.append("CAST(").append(qc).append(" AS TEXT) LIKE ? ESCAPE '\\'");
                    args.add(esc(text) + "%");
                    break;
                case 3:
                    sb.append("CAST(").append(qc).append(" AS TEXT) LIKE ? ESCAPE '\\'");
                    args.add("%" + esc(text));
                    break;
                case 4:
                    sb.append(qc).append(" > ?");
                    args.add(text);
                    break;
                case 5:
                    sb.append(qc).append(" < ?");
                    args.add(text);
                    break;
                case 6:
                    sb.append(qc).append(" IS NULL");
                    break;
                default:
                    sb.append(qc).append(" IS NOT NULL");
                    break;
            }
        }
        f.where = " WHERE " + sb;
        f.args = args.toArray(new String[0]);
        return f;
    }

    public static long count(SQLiteDatabase db, String table, Filter f) {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + q(table) + f.where, f.args);
        try {
            return c.moveToFirst() ? c.getLong(0) : 0;
        } finally {
            c.close();
        }
    }

    // QUERY

    /** Browse one page of a table. Includes rowid when possible so rows can be edited. */
    public static QueryResult browse(SQLiteDatabase db, String table, int limit, int offset, Filter f) {
        String tail = " FROM " + q(table) + f.where + " LIMIT " + limit + " OFFSET " + offset;
        try {
            return query(db, "SELECT rowid AS _rid_, *" + tail, f.args, true, limit);
        } catch (Exception e) {
            return query(db, "SELECT *" + tail, f.args, false, limit);
        }
    }

    public static QueryResult query(SQLiteDatabase db, String sql, String[] args, boolean hasRowId, int max) {
        QueryResult r = new QueryResult();
        r.hasRowId = hasRowId;
        Cursor c = db.rawQuery(sql, args);
        try {
            int n = c.getColumnCount();
            int start = hasRowId ? 1 : 0;
            r.columns = new String[n - start];
            for (int i = start; i < n; i++) r.columns[i - start] = c.getColumnName(i);
            while (c.moveToNext() && r.rows.size() < max) {
                if (hasRowId) r.rowIds.add(c.getLong(0));
                String[] row = new String[n - start];
                for (int i = start; i < n; i++) row[i - start] = cell(c, i);
                r.rows.add(row);
            }
        } finally {
            c.close();
        }
        return r;
    }

    private static String cell(Cursor c, int i) {
        switch (c.getType(i)) {
            case Cursor.FIELD_TYPE_NULL:
                return "NULL";
            case Cursor.FIELD_TYPE_BLOB:
                return "[BLOB " + c.getBlob(i).length + " B]";
            default:
                return c.getString(i);
        }
    }



    /** Run a script with one or more statements. Returns the last result set (if any). */
    public static QueryResult execute(SQLiteDatabase db, String script) {
        long t0 = System.currentTimeMillis();
        QueryResult last = null;
        int count = 0;
        for (String s : split(script)) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            String kw = t.split("\\s+")[0].toLowerCase();
            if (kw.equals("select") || kw.equals("pragma") || kw.equals("with") || kw.equals("explain")) {
                last = query(db, t, null, false, MAX_ROWS);
            } else {
                db.execSQL(t);
            }
            count++;
        }
        QueryResult r = last != null ? last : new QueryResult();
        r.statements = count;
        r.millis = System.currentTimeMillis() - t0;
        return r;
    }

    //  SPLITTER

    /** Split by ';' (ignoring quotes) and keep CREATE TRIGGER ... BEGIN ... END together */
    private static List<String> split(String s) {
        List<String> raw = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        char quote = 0;
        for (char ch : s.toCharArray()) {
            if (quote != 0) {
                sb.append(ch);
                if (ch == quote) quote = 0;
            } else if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                sb.append(ch);
            } else if (ch == ';') {
                raw.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(ch);
            }
        }
        if (sb.length() > 0) raw.add(sb.toString());

        List<String> out = new ArrayList<>();
        StringBuilder pending = null;
        for (String part : raw) {
            if (pending != null) {
                pending.append(';').append(part);
                if (endsWithEnd(part)) {
                    out.add(pending.toString());
                    pending = null;
                }
            } else if (isTrigger(part) && !endsWithEnd(part)) {
                pending = new StringBuilder(part);
            } else {
                out.add(part);
            }
        }
        if (pending != null) out.add(pending.toString());
        return out;
    }

    private static boolean isTrigger(String p) {
        return p.trim().toUpperCase().matches("(?s)CREATE\\s+(TEMP\\s+|TEMPORARY\\s+)?TRIGGER\\b.*");
    }

    private static boolean endsWithEnd(String p) {
        return p.trim().toUpperCase().matches("(?s).*\\bEND$");
    }
}