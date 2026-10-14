package com.dya.sqlitebrowser.Util;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.net.Uri;

import com.dya.sqlitebrowser.Database.DbHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts JSON into SQLite tables.
 *  - [ {..}, {..} ]                 -> one table
 *  - { "a": [..], "b": [..] }       -> one table per array
 *  - { "k1": {..}, "k2": {..} }     -> one table (column _key + fields)
 *  - one JSON object per line       -> one table (JSON Lines)
 * Nested objects are flattened (address_city).
 * Arrays of objects inside a row become child tables (parent_child) linked by
 * an _id column in the parent and a "<parent>_id" column in the child.
 * Arrays of plain values are stored as JSON text.
 */
public class JsonImporter {

    public static class Result {
        public final List<String> tables = new ArrayList<>();
        public int rows = 0;
    }

    private static class Col {
        boolean seen = false, allInt = true, allNum = true;

        void add(Object v) {
            if (v == null) return;
            seen = true;
            if (v instanceof Long) return;
            allInt = false;
            if (!(v instanceof Double)) allNum = false;
        }

        String type() {
            if (!seen) return "TEXT";
            if (allInt) return "INTEGER";
            if (allNum) return "REAL";
            return "TEXT";
        }
    }

    // ------------------------------------------------------------ helpers

    public static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    /** File name without extension, safe to use as a table / database name */
    public static String baseName(Uri uri) {
        String s = uri.getLastPathSegment();
        if (s == null) return "imported";
        s = s.substring(Math.max(s.lastIndexOf('/'), s.lastIndexOf(':')) + 1);
        int dot = s.lastIndexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        s = s.replaceAll("[^\\p{L}\\p{N}_]+", "_");
        return s.isEmpty() ? "imported" : s;
    }

    private static String lc(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------- import

    public static Result importInto(SQLiteDatabase db, String json, String defaultName) throws JSONException {
        if (json == null) throw new JSONException("Empty file");
        json = json.trim();
        if (!json.isEmpty() && json.charAt(0) == '\uFEFF') json = json.substring(1).trim();
        if (json.isEmpty()) throw new JSONException("Empty file");

        JSONArray lines = tryJsonLines(json);
        Object root = lines != null ? lines : new JSONTokener(json).nextValue();

        Map<String, JSONArray> sets = new LinkedHashMap<>();
        if (root instanceof JSONArray) {
            sets.put(defaultName, (JSONArray) root);
        } else if (root instanceof JSONObject) {
            JSONObject o = (JSONObject) root;
            boolean anyArray = false;
            boolean allObjects = o.length() > 0;
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = o.opt(k);
                if (v instanceof JSONArray) {
                    sets.put(k, (JSONArray) v);
                    anyArray = true;
                }
                if (!(v instanceof JSONObject)) allObjects = false;
            }
            if (!anyArray) {
                JSONArray one = new JSONArray();
                if (allObjects) {
                    Iterator<String> keys = o.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        JSONObject row = new JSONObject();
                        row.put("_key", k);
                        JSONObject v = o.getJSONObject(k);
                        Iterator<String> vi = v.keys();
                        while (vi.hasNext()) {
                            String vk = vi.next();
                            row.put(vk, v.opt(vk));
                        }
                        one.put(row);
                    }
                } else {
                    one.put(o);
                }
                sets.put(defaultName, one);
            }
        } else {
            throw new JSONException("The top-level JSON value must be an object or an array");
        }

        Set<String> used = new HashSet<>();
        Cursor c = db.rawQuery("SELECT name FROM sqlite_master", null);
        while (c.moveToNext()) used.add(lc(c.getString(0)));
        c.close();

        Result res = new Result();
        db.beginTransaction();
        try {
            for (Map.Entry<String, JSONArray> e : sets.entrySet()) {
                importArray(db, e.getKey(), e.getValue(), used, res);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return res;
    }

    /** JSON Lines: every non-empty line is one JSON object (needs 2+ lines) */
    private static JSONArray tryJsonLines(String s) {
        if (s.charAt(0) != '{') return null;
        JSONArray arr = new JSONArray();
        int count = 0;
        for (String ln : s.split("\\r?\\n")) {
            String t = ln.trim();
            if (t.isEmpty()) continue;
            if (t.charAt(0) != '{' || t.charAt(t.length() - 1) != '}') return null;
            try {
                arr.put(new JSONObject(t));
            } catch (JSONException e) {
                return null;
            }
            count++;
        }
        return count > 1 ? arr : null;
    }

    private static void importArray(SQLiteDatabase db, String desired, JSONArray arr,
                                    Set<String> used, Result res) throws JSONException {
        int n = arr.length();
        if (n == 0) return;

        List<Map<String, Object>> rows = new ArrayList<>(n);
        List<Map<String, JSONArray>> kids = new ArrayList<>(n);
        boolean hasKids = false;

        for (int i = 0; i < n; i++) {
            Object el = arr.opt(i);
            Map<String, Object> row = new LinkedHashMap<>();
            Map<String, JSONArray> kid = new LinkedHashMap<>();
            if (el instanceof JSONObject) {
                flatten("", (JSONObject) el, row, kid);
            } else if (el instanceof JSONArray) {
                JSONArray a = (JSONArray) el;
                for (int j = 0; j < a.length(); j++) row.put("column" + (j + 1), norm(a.opt(j)));
            } else {
                row.put("value", norm(el));
            }
            if (!kid.isEmpty()) hasKids = true;
            rows.add(row);
            kids.add(kid);
        }

        // parents get an _id column (first) so children can point to them.
        // A data field that is already called _id is kept as _id_orig.
        if (hasKids) {
            for (int i = 0; i < n; i++) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("_id", (long) (i + 1));
                for (Map.Entry<String, Object> en : rows.get(i).entrySet()) {
                    String key = en.getKey().equals("_id") ? "_id_orig" : en.getKey();
                    r.put(key, en.getValue());
                }
                rows.set(i, r);
            }
        }

        LinkedHashMap<String, Col> cols = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            for (Map.Entry<String, Object> en : row.entrySet()) {
                Col c = cols.get(en.getKey());
                if (c == null) {
                    c = new Col();
                    cols.put(en.getKey(), c);
                }
                c.add(en.getValue());
            }
        }
        if (cols.isEmpty()) return;
        if (cols.size() > 999) {
            throw new JSONException("Too many columns (" + cols.size() + "), the limit is 999");
        }

        // unique column names (SQLite is case-insensitive)
        List<String> keys = new ArrayList<>(cols.keySet());
        String[] names = new String[keys.size()];
        Set<String> usedCols = new HashSet<>();
        for (int i = 0; i < keys.size(); i++) {
            String base = keys.get(i).trim();
            if (base.isEmpty()) base = "column" + (i + 1);
            String nm = base;
            int k = 2;
            while (!usedCols.add(lc(nm))) nm = base + "_" + (k++);
            names[i] = nm;
        }

        String table = uniqueTable(desired, used);

        StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(DbHelper.q(table)).append(" (");
        StringBuilder colList = new StringBuilder();
        StringBuilder marks = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if (i > 0) {
                ddl.append(", ");
                colList.append(", ");
                marks.append(", ");
            }
            String type = (hasKids && i == 0) ? "INTEGER PRIMARY KEY"
                    : cols.get(keys.get(i)).type();
            ddl.append(DbHelper.q(names[i])).append(' ').append(type);
            colList.append(DbHelper.q(names[i]));
            marks.append('?');
        }
        ddl.append(')');
        db.execSQL(ddl.toString());

        SQLiteStatement st = db.compileStatement("INSERT INTO " + DbHelper.q(table)
                + " (" + colList + ") VALUES (" + marks + ")");
        try {
            for (Map<String, Object> row : rows) {
                st.clearBindings();
                for (int i = 0; i < keys.size(); i++) {
                    Object v = row.get(keys.get(i));
                    if (v == null) continue; // stays NULL
                    if (v instanceof Long) st.bindLong(i + 1, (Long) v);
                    else if (v instanceof Double) st.bindDouble(i + 1, (Double) v);
                    else st.bindString(i + 1, v.toString());
                }
                st.executeInsert();
            }
        } finally {
            st.close();
        }
        res.tables.add(table);
        res.rows += rows.size();

        // child tables: one per nested array of objects
        if (hasKids) {
            String parentCol = table + "_id";
            Map<String, JSONArray> merged = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                long id = i + 1;
                for (Map.Entry<String, JSONArray> en : kids.get(i).entrySet()) {
                    JSONArray target = merged.get(en.getKey());
                    if (target == null) {
                        target = new JSONArray();
                        merged.put(en.getKey(), target);
                    }
                    JSONArray src = en.getValue();
                    for (int j = 0; j < src.length(); j++) {
                        JSONObject o = src.getJSONObject(j);
                        JSONObject c = new JSONObject();
                        c.put(parentCol, id);
                        Iterator<String> it = o.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            // a data field that already has the link column's name is kept as *_orig
                            String key = k.equals(parentCol) ? parentCol + "_orig" : k;
                            c.put(key, o.opt(k));
                        }
                        target.put(c);
                    }
                }
            }
            for (Map.Entry<String, JSONArray> en : merged.entrySet()) {
                importArray(db, table + "_" + en.getKey(), en.getValue(), used, res);
            }
        }
    }

    private static boolean isObjectArray(JSONArray a) {
        if (a.length() == 0) return false;
        for (int i = 0; i < a.length(); i++) {
            if (!(a.opt(i) instanceof JSONObject)) return false;
        }
        return true;
    }

    private static void flatten(String prefix, JSONObject o, Map<String, Object> out,
                                Map<String, JSONArray> kids) {
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = o.opt(k);
            if (v instanceof JSONObject) {
                JSONObject child = (JSONObject) v;
                if (child.length() == 0) out.put(prefix + k, null);
                else flatten(prefix + k + "_", child, out, kids);
            } else if (v instanceof JSONArray && isObjectArray((JSONArray) v)) {
                kids.put(prefix + k, (JSONArray) v);   // becomes its own table
            } else {
                out.put(prefix + k, norm(v));
            }
        }
    }

    /** null / Long / Double / String only */
    private static Object norm(Object v) {
        if (v == null || v == JSONObject.NULL) return null;
        if (v instanceof Boolean) return ((Boolean) v) ? 1L : 0L;
        if (v instanceof Integer) return ((Integer) v).longValue();
        if (v instanceof Long) return v;
        if (v instanceof Double) return v;
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) return v;
        return v.toString(); // plain-value JSONArray / empty array as JSON text
    }

    private static String uniqueTable(String desired, Set<String> used) {
        String base = desired == null ? "" : desired.trim();
        if (base.isEmpty()) base = "data";
        if (lc(base).startsWith("sqlite_")) base = "t_" + base;
        String name = base;
        int k = 2;
        while (!used.add(lc(name))) name = base + "_" + (k++);
        return name;
    }
}