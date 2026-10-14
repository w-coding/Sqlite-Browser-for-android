package com.dya.sqlitebrowser.Activities;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dya.sqlitebrowser.Adapter.GridAdapter;
import com.dya.sqlitebrowser.Database.DbHelper;
import com.dya.sqlitebrowser.Database.QueryResult;
import com.dya.sqlitebrowser.R;
import com.dya.sqlitebrowser.Util.CsvUtil;
import com.dya.sqlitebrowser.Util.JsonImporter;
import com.dya.sqlitebrowser.Util.LocaleHelper;
import com.dya.sqlitebrowser.app.App;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONException;

import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DbActivity extends AppCompatActivity {

    private static final int PAGE = 100;
    private static final String[] TYPES = {"TEXT", "INTEGER", "REAL", "BLOB", "NUMERIC", "TEXT_NUM"};

    private interface Str {
        void run(String s);
    }

    private SQLiteDatabase db;
    private TabLayout tabs;
    private View panelStructure, panelData, panelSql;
    private TextView tvSchema, tvPage, tvStatus;
    private Spinner spinner, spColumn, spOp;
    private LinearLayout headerData, headerSql;
    private RecyclerView rvData, rvSql;
    private EditText etSql, etSearch;

    private QueryResult current;
    private int page = 0;
    private long total = 0;

    // active filter
    private String fCol = null;
    private int fOp = 0;
    private String fText = "";

    private ActivityResultLauncher<String> csvExportLauncher;
    private ActivityResultLauncher<String[]> csvImportLauncher;
    private ActivityResultLauncher<String[]> jsonImportLauncher;
    private String dbPath;
    private ActivityResultLauncher<String> dbExportLauncher;
    

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_db);
        EdgeToEdge.enable(this);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_db), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        String path = getIntent().getStringExtra("path");

        setTitle(new File(path).getName());

        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        try {
            db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE);
        } catch (Exception e) {
            Toast.makeText(this, fmt(R.string.err_cannot_open, e.getMessage()), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        dbPath = path;

        csvExportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/csv"), uri -> {
                    if (uri != null) exportCsv(uri);
                });

        dbExportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/octet-stream"), uri -> {
                    if (uri != null) exportDb(uri);
                });

        csvImportLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null) importCsv(uri);
                });

        jsonImportLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null) importJson(uri);
                });

        panelStructure = findViewById(R.id.panelStructure);
        panelData = findViewById(R.id.panelData);
        panelSql = findViewById(R.id.panelSql);
        tvSchema = findViewById(R.id.tvSchema);
        tvPage = findViewById(R.id.tvPage);
        tvStatus = findViewById(R.id.tvStatus);
        spinner = findViewById(R.id.spinner);
        spColumn = findViewById(R.id.spColumn);
        spOp = findViewById(R.id.spOp);
        etSearch = findViewById(R.id.etSearch);
        headerData = findViewById(R.id.headerData);
        headerSql = findViewById(R.id.headerSql);
        rvData = findViewById(R.id.rvData);
        rvSql = findViewById(R.id.rvSql);
        etSql = findViewById(R.id.etSql);

        rvData.setLayoutManager(new LinearLayoutManager(this));
        rvSql.setLayoutManager(new LinearLayoutManager(this));

        String[] ops = getResources().getStringArray(R.array.filter_ops);
        spOp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ops));

        tabs = findViewById(R.id.tabs);
        tabs.addTab(tabs.newTab().setText(R.string.tab_structure));
        tabs.addTab(tabs.newTab().setText(R.string.tab_data));
        tabs.addTab(tabs.newTab().setText(R.string.tab_sql));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showPanel(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // table changed: reset filter and rebuild column list
                page = 0;
                fCol = null;
                fOp = 0;
                fText = "";
                etSearch.setText("");
                spOp.setSelection(0);
                String t = selectedTable();
                List<String> cl = new ArrayList<>();
                cl.add(getString(R.string.all_columns));
                if (t != null) cl.addAll(DbHelper.getColumns(db, t));
                spColumn.setAdapter(new ArrayAdapter<>(DbActivity.this,
                        android.R.layout.simple_spinner_dropdown_item, cl));
                loadData();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        findViewById(R.id.btnNewTable).setOnClickListener(v -> newTableDialog());
        findViewById(R.id.btnDropTable).setOnClickListener(v -> dropTableDialog());
        findViewById(R.id.btnAlter).setOnClickListener(v -> alterTableDialog());
        findViewById(R.id.btnIndexes).setOnClickListener(v -> indexManagerDialog());
        findViewById(R.id.btnPrev).setOnClickListener(v -> {
            if (page > 0) {
                page--;
                loadData();
            }
        });
        findViewById(R.id.btnNext).setOnClickListener(v -> {
            if ((page + 1) * PAGE < total) {
                page++;
                loadData();
            }
        });
        findViewById(R.id.btnAdd).setOnClickListener(v -> addRowDialog());
        findViewById(R.id.btnRun).setOnClickListener(v -> runSql());
        findViewById(R.id.btnSearch).setOnClickListener(v -> doSearch());
        findViewById(R.id.btnClear).setOnClickListener(v -> clearSearch());
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            doSearch();
            return true;
        });

        showPanel(0);
        refresh();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        if (db != null && db.isOpen()) db.close();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_db, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_theme) {
            App.chooseTheme(this);
            return true;
        } else if (id == R.id.action_language) {
            LocaleHelper.choose(this);
            return true;
        } else if (id == R.id.action_export_csv) {
            String t = selectedTable();
            if (t == null) toast(getString(R.string.toast_no_table));
            else csvExportLauncher.launch(t + ".csv");
            return true;
        } else if (id == R.id.action_import_csv) {
            csvImportLauncher.launch(new String[]{"*/*"});
            return true;
        } else if (id == R.id.action_import_json) {
            jsonImportLauncher.launch(new String[]{"*/*"});
            return true;
        } else if (id == R.id.action_export_db) {
            dbExportLauncher.launch(new File(dbPath).getName());
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // HELPERS

    private static String q(String s) {
        return DbHelper.q(s);
    }

    /** Formats a string resource with Latin digits */
    private String fmt(int res, Object... args) {
        return String.format(Locale.US, getString(res), args);
    }

    private void exportDb(Uri uri) {
        // Flush any pending WAL data into the main .db file so the copy is complete
        try {
            Cursor c = db.rawQuery("PRAGMA wal_checkpoint(FULL)", null);
            c.moveToFirst();
            c.close();
        } catch (Exception ignored) {
        }

        final File src = new File(dbPath);
        new Thread(() -> {
            try (InputStream in = new java.io.FileInputStream(src);
                 OutputStream os = getContentResolver().openOutputStream(uri)) {
                byte[] buf = new byte[64 * 1024];
                int len;
                while ((len = in.read(buf)) > 0) os.write(buf, 0, len);
                os.flush();
                runOnUiThread(() -> toast(getString(R.string.exported)));
            } catch (Exception e) {
                runOnUiThread(() -> toast(fmt(R.string.export_failed, e.getMessage())));
            }
        }).start();
    }

    private void toast(String s) {
        View view = LayoutInflater.from(this)
                .inflate(R.layout.layout_custom_toast, null);

        TextView textView = view.findViewById(R.id.toast_text);
        textView.setText(s);

        TypedValue value = new TypedValue();

        if (getTheme().resolveAttribute(R.attr.toastBackgroundColor, value, true)) {
            view.getBackground().setTint(value.data);
        }

        if (getTheme().resolveAttribute(R.attr.toastTextColor, value, true)) {
            textView.setTextColor(value.data);
        }

        Toast toast = new Toast(getApplicationContext());
        toast.setDuration(Toast.LENGTH_SHORT);
        toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 100);
        toast.setView(view);
        toast.show();
    }

    private void err(Exception e) {
        toast(fmt(R.string.err_generic, e.getMessage()));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void showPanel(int i) {
        panelStructure.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        panelData.setVisibility(i == 1 ? View.VISIBLE : View.GONE);
        panelSql.setVisibility(i == 2 ? View.VISIBLE : View.GONE);
    }

    private String selectedTable() {
        Object o = spinner.getSelectedItem();
        return o == null ? null : o.toString();
    }

    private LinearLayout vbox() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(20), dp(8), dp(20), 0);
        return ll;
    }

    private void prompt(String title, String initial, Str cb) {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(initial);
        et.setSelection(et.getText().length());
        LinearLayout ll = vbox();
        ll.addView(et);
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(ll)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String s = et.getText().toString().trim();
                    if (!s.isEmpty()) cb.run(s);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirm(String title, String msg, Runnable ok) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(R.string.yes, (d, w) -> ok.run())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void runDdl(String sql) {
        try {
            db.execSQL(sql);
            refresh();
            toast(getString(R.string.toast_done));
        } catch (Exception e) {
            err(e);
        }
    }

    /** Reload table list + schema (call after structure changes) */
    private void refresh() {
        List<String> tables = DbHelper.getTables(db);
        String sel = selectedTable();
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, tables));
        if (sel != null && tables.contains(sel)) spinner.setSelection(tables.indexOf(sel));

        String schema = DbHelper.getSchema(db);
        tvSchema.setText(schema.isEmpty() ? getString(R.string.schema_empty) : schema);

        if (tables.isEmpty()) {
            clearGrid(headerData, rvData);
            tvPage.setText(R.string.no_tables);
            current = null;
        }
    }

    // DATA TAB

    private void doSearch() {
        int ci = spColumn.getSelectedItemPosition();
        fCol = (ci <= 0 || spColumn.getSelectedItem() == null) ? null : spColumn.getSelectedItem().toString();
        fOp = spOp.getSelectedItemPosition();
        fText = etSearch.getText().toString();
        page = 0;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
        loadData();
    }

    private void clearSearch() {
        etSearch.setText("");
        spOp.setSelection(0);
        if (spColumn.getAdapter() != null && spColumn.getAdapter().getCount() > 0) spColumn.setSelection(0);
        fCol = null;
        fOp = 0;
        fText = "";
        page = 0;
        loadData();
    }

    private DbHelper.Filter currentFilter(String table) {
        return DbHelper.buildFilter(DbHelper.getColumns(db, table), fCol, fOp, fText);
    }

    private void loadData() {
        String t = selectedTable();
        if (t == null) return;
        try {
            DbHelper.Filter f = currentFilter(t);
            total = DbHelper.count(db, t, f);
            if (page > 0 && (long) page * PAGE >= total) page = 0;
            current = DbHelper.browse(db, t, PAGE, page * PAGE, f);
            long from = total == 0 ? 0 : (long) page * PAGE + 1;
            long to = Math.min((page + 1L) * PAGE, total);

            StringBuilder info = new StringBuilder(fmt(R.string.page_info, from, to, total));
            if (f.active()) info.append("  ").append(getString(R.string.suffix_filtered));
            if (!current.hasRowId) info.append("  ").append(getString(R.string.suffix_readonly));
            tvPage.setText(info.toString());

            bindGrid(headerData, rvData, current, this::editRowDialog);
        } catch (Exception e) {
            err(e);
        }
    }

    private void bindGrid(LinearLayout header, RecyclerView rv, QueryResult r, GridAdapter.Listener l) {
        int w = dp(130);
        header.removeAllViews();
        for (String c : r.columns) {
            TextView tv = GridAdapter.makeCell(this, w, true);
            tv.setText(c);
            header.addView(tv);
        }
        ViewGroup.LayoutParams lp = rv.getLayoutParams();
        lp.width = w * Math.max(1, r.columns.length);
        rv.setLayoutParams(lp);
        rv.setAdapter(new GridAdapter(r, w, l));
    }

    private void clearGrid(LinearLayout header, RecyclerView rv) {
        header.removeAllViews();
        rv.setAdapter(null);
    }

    private ScrollView buildForm(String[] cols, int[] kinds, String[] vals, List<EditText> out) {
        LinearLayout ll = vbox();
        for (int i = 0; i < cols.length; i++) {
            TextInputLayout til = new TextInputLayout(this);
            til.setHint(cols[i]);
            TextInputEditText et = new TextInputEditText(til.getContext());
            // INTEGER column -> number keyboard, REAL/NUMERIC column -> number keyboard with decimal point
            if (kinds[i] == DbHelper.KIND_INT) {
                et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
            } else if (kinds[i] == DbHelper.KIND_REAL) {
                et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
                        | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            } else if (kinds[i] == DbHelper.KIND_DIGITS) {
                et.setInputType(InputType.TYPE_CLASS_NUMBER); // digits only, saved as text (07 stays 07)
            }
            if (vals != null) {
                String v = vals[i];
                // a NULL number is shown as an empty field (the hint shows the column name)
                if (kinds[i] != DbHelper.KIND_TEXT && "NULL".equals(v)) v = "";
                et.setText(v);
            }
            til.addView(et);
            ll.addView(til);
            out.add(et);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(ll);
        return sv;
    }

    private void editRowDialog(int pos) {
        if (current == null) return;
        if (!current.hasRowId) {
            toast(getString(R.string.read_only_msg));
            return;
        }
        final String table = selectedTable();
        final String[] original = current.rows.get(pos);
        final long rowId = current.rowIds.get(pos);
        final String[] cols = current.columns;
        final int[] kinds = DbHelper.getKinds(db, table, cols);
        final List<EditText> fields = new ArrayList<>();
        ScrollView form = buildForm(cols, kinds, original, fields);

        new MaterialAlertDialogBuilder(this)
                .setTitle(fmt(R.string.edit_row_title, rowId))
                .setView(form)
                .setPositiveButton(R.string.save, (d, w) -> {
                    ContentValues cv = new ContentValues();
                    for (int i = 0; i < cols.length; i++) {
                        String s = fields.get(i).getText().toString();
                        if (kinds[i] != DbHelper.KIND_TEXT) {
                            s = DbHelper.normalizeNumber(s);
                            if (s.isEmpty() && original[i].isEmpty()) continue;
                            if (s.isEmpty()) s = "NULL"; // an empty number field means NULL
                        }
                        if (s.equals(original[i])) continue; // unchanged (also protects BLOBs)
                        if (s.equals("NULL")) cv.putNull(q(cols[i]));
                        else cv.put(q(cols[i]), s);
                    }
                    if (cv.size() == 0) return;
                    try {
                        db.update(q(table), cv, "rowid=?", new String[]{String.valueOf(rowId)});
                        loadData();
                    } catch (Exception e) {
                        err(e);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.delete, (d, w) -> new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.delete_row_title)
                        .setPositiveButton(R.string.delete, (d2, w2) -> {
                            try {
                                db.delete(q(table), "rowid=?", new String[]{String.valueOf(rowId)});
                                loadData();
                            } catch (Exception e) {
                                err(e);
                            }
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show())
                .show();
    }

    private void addRowDialog() {
        final String table = selectedTable();
        if (table == null) return;
        final String[] cols = DbHelper.getColumns(db, table).toArray(new String[0]);
        final int[] kinds = DbHelper.getKinds(db, table, cols);
        final List<EditText> fields = new ArrayList<>();
        ScrollView form = buildForm(cols, kinds, null, fields);

        new MaterialAlertDialogBuilder(this)
                .setTitle(fmt(R.string.add_row_title, table))
                .setMessage(R.string.add_row_msg)
                .setView(form)
                .setPositiveButton(R.string.insert, (d, w) -> {
                    ContentValues cv = new ContentValues();
                    for (int i = 0; i < cols.length; i++) {
                        String s = fields.get(i).getText().toString();
                        if (kinds[i] != DbHelper.KIND_TEXT) s = DbHelper.normalizeNumber(s);
                        if (s.isEmpty()) continue;
                        if (s.equals("NULL")) cv.putNull(q(cols[i]));
                        else cv.put(q(cols[i]), s);
                    }
                    try {
                        if (cv.size() == 0) db.execSQL("INSERT INTO " + q(table) + " DEFAULT VALUES");
                        else db.insertOrThrow(q(table), null, cv);
                        loadData();
                    } catch (Exception e) {
                        err(e);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // CSV EXPORT

    private void exportCsv(Uri uri) {
        final String t = selectedTable();
        if (t == null) return;
        final DbHelper.Filter f = currentFilter(t);
        new Thread(() -> {
            int n = 0;
            try (OutputStream os = getContentResolver().openOutputStream(uri);
                 Writer w = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
                 Cursor c = db.rawQuery("SELECT * FROM " + q(t) + f.where, f.args)) {
                w.write('\uFEFF'); // BOM so Excel reads UTF-8 correctly
                int cc = c.getColumnCount();
                String[] head = new String[cc];
                for (int i = 0; i < cc; i++) head[i] = c.getColumnName(i);
                w.write(CsvUtil.line(head));
                w.write("\r\n");
                while (c.moveToNext()) {
                    String[] row = new String[cc];
                    for (int i = 0; i < cc; i++) {
                        int type = c.getType(i);
                        row[i] = (type == Cursor.FIELD_TYPE_NULL || type == Cursor.FIELD_TYPE_BLOB)
                                ? "" : c.getString(i);
                    }
                    w.write(CsvUtil.line(row));
                    w.write("\r\n");
                    n++;
                }
                final int rows = n;
                runOnUiThread(() -> toast(fmt(R.string.csv_exported, rows)));
            } catch (Exception e) {
                runOnUiThread(() -> err(e));
            }
        }).start();
    }

    // CSV IMPORT

    private void importCsv(Uri uri) {
        final String defName = JsonImporter.baseName(uri);
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                final List<String[]> rows = CsvUtil.parse(new InputStreamReader(in, "UTF-8"));
                runOnUiThread(() -> importCsvDialog(rows, defName));
            } catch (Exception e) {
                runOnUiThread(() -> err(e));
            }
        }).start();
    }

    private void importCsvDialog(final List<String[]> rows, String defName) {
        if (rows.isEmpty()) {
            toast(getString(R.string.csv_empty));
            return;
        }
        LinearLayout ll = vbox();
        final EditText etName = new EditText(this);
        etName.setSingleLine(true);
        etName.setText(defName);
        final CheckBox cbHeader = new CheckBox(this);
        cbHeader.setText(R.string.csv_header);
        cbHeader.setChecked(true);
        ll.addView(etName);
        ll.addView(cbHeader);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.csv_title)
                .setMessage(fmt(R.string.csv_msg, rows.size()))
                .setView(ll)
                .setPositiveButton(R.string.import_label, (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) return;
                    doImport(rows, name, cbHeader.isChecked());
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void doImport(final List<String[]> rows, final String table, final boolean header) {
        new Thread(() -> {
            try {
                int n = 0;
                for (String[] r : rows) n = Math.max(n, r.length);

                // column names from CSV
                String[] head = new String[n];
                for (int i = 0; i < n; i++) {
                    String h = (header && i < rows.get(0).length) ? rows.get(0)[i].trim() : "";
                    if (h.isEmpty()) h = "column" + (i + 1);
                    head[i] = h;
                }
                // make names unique (case-insensitive)
                List<String> seen = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    String h = head[i];
                    int k = 2;
                    while (seen.contains(h.toLowerCase())) h = head[i] + "_" + (k++);
                    head[i] = h;
                    seen.add(h.toLowerCase());
                }

                boolean exists = false;
                for (String t : DbHelper.getTablesOnly(db)) if (t.equalsIgnoreCase(table)) exists = true;

                int start = header ? 1 : 0;
                int count = 0;

                db.beginTransaction();
                try {
                    if (!exists) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < n; i++) {
                            if (i > 0) sb.append(", ");
                            sb.append(q(head[i])).append(" TEXT");
                        }
                        db.execSQL("CREATE TABLE " + q(table) + " (" + sb + ")");
                    }
                    List<String> tcols = DbHelper.getColumns(db, table);
                    String[] target = new String[n];
                    for (int i = 0; i < n; i++) {
                        for (String tc : tcols) if (tc.equalsIgnoreCase(head[i])) target[i] = tc;
                    }
                    for (int r = start; r < rows.size(); r++) {
                        String[] row = rows.get(r);
                        ContentValues cv = new ContentValues();
                        for (int i = 0; i < n; i++) {
                            if (target[i] == null) continue;
                            String v = i < row.length ? row[i] : "";
                            if (v.isEmpty()) cv.putNull(q(target[i]));
                            else cv.put(q(target[i]), v);
                        }
                        if (cv.size() == 0) continue;
                        db.insertOrThrow(q(table), null, cv);
                        count++;
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
                final int done = count;
                runOnUiThread(() -> {
                    refresh();
                    toast(fmt(R.string.csv_imported, done, table));
                });
            } catch (Exception e) {
                runOnUiThread(() -> err(e));
            }
        }).start();
    }

    // JSON IMPORT

    /** Converts a JSON file into tables inside this database */
    private void importJson(Uri uri) {
        final String defName = JsonImporter.baseName(uri);
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                String json = JsonImporter.readAll(in);
                final JsonImporter.Result r = JsonImporter.importInto(db, json, defName);
                runOnUiThread(() -> {
                    refresh();
                    if (r.tables.isEmpty()) {
                        toast(getString(R.string.json_nothing));
                    } else {
                        toast(fmt(R.string.json_imported, r.tables.size(), r.rows,
                                TextUtils.join(", ", r.tables)));
                    }
                });
            } catch (OutOfMemoryError oom) {
                runOnUiThread(() -> toast(getString(R.string.json_too_big)));
            } catch (JSONException je) {
                runOnUiThread(() -> toast(fmt(R.string.json_invalid, je.getMessage())));
            } catch (Exception e) {
                runOnUiThread(() -> err(e));
            }
        }).start();
    }

    // STRUCTURE TAB

    private void addColumnRow(LinearLayout box, String name, boolean pk) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        EditText et = new EditText(this);
        et.setHint(R.string.hint_column);
        et.setSingleLine(true);
        et.setText(name);
        et.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, TYPES));
        if (pk) sp.setSelection(1); // INTEGER

        CheckBox cbPk = new CheckBox(this);
        cbPk.setText("PK");
        cbPk.setChecked(pk);

        CheckBox cbNn = new CheckBox(this);
        cbNn.setText("NN");

        row.addView(et);
        row.addView(sp);
        row.addView(cbPk);
        row.addView(cbNn);
        box.addView(row);
    }

    /** Create a table from the UI (column builder), or jump to the SQL tab */
    private void newTableDialog() {
        LinearLayout root = vbox();

        final EditText etName = new EditText(this);
        etName.setHint(R.string.hint_table_name);
        etName.setSingleLine(true);

        TextView legend = new TextView(this);
        legend.setText(R.string.legend_pk);
        legend.setTextSize(12);
        legend.setPadding(0, dp(8), 0, dp(4));

        final LinearLayout colBox = new LinearLayout(this);
        colBox.setOrientation(LinearLayout.VERTICAL);
        addColumnRow(colBox, "id", true);
        addColumnRow(colBox, "name", false);

        MaterialButton btnAdd = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnAdd.setText(R.string.btn_add_column_row);
        btnAdd.setOnClickListener(v -> addColumnRow(colBox, "", false));

        root.addView(etName);
        root.addView(legend);
        root.addView(colBox);
        root.addView(btnAdd);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.btn_new_table)
                .setView(sv)
                .setPositiveButton(R.string.create, (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast(getString(R.string.err_table_name));
                        return;
                    }
                    StringBuilder defs = new StringBuilder();
                    for (int i = 0; i < colBox.getChildCount(); i++) {
                        LinearLayout row = (LinearLayout) colBox.getChildAt(i);
                        String cn = ((EditText) row.getChildAt(0)).getText().toString().trim();
                        if (cn.isEmpty()) continue;
                        String type = TYPES[((Spinner) row.getChildAt(1)).getSelectedItemPosition()];
                        boolean pk = ((CheckBox) row.getChildAt(2)).isChecked();
                        boolean nn = ((CheckBox) row.getChildAt(3)).isChecked();
                        if (defs.length() > 0) defs.append(", ");
                        defs.append(q(cn)).append(' ').append(type);
                        if (pk) defs.append(type.equals("INTEGER") ? " PRIMARY KEY AUTOINCREMENT" : " PRIMARY KEY");
                        else if (nn) defs.append(" NOT NULL");
                    }
                    if (defs.length() == 0) {
                        toast(getString(R.string.err_no_columns));
                        return;
                    }
                    runDdl("CREATE TABLE " + q(name) + " (" + defs + ")");
                })
                .setNeutralButton(R.string.use_sql, (d, w) -> {
                    etSql.setText("CREATE TABLE table_name (\n  id INTEGER PRIMARY KEY AUTOINCREMENT,\n  name TEXT NOT NULL\n);");
                    TabLayout.Tab t = tabs.getTabAt(2);
                    if (t != null) t.select();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void dropTableDialog() {
        List<String> tables = DbHelper.getTablesOnly(db);
        if (tables.isEmpty()) {
            toast(getString(R.string.no_tables));
            return;
        }
        final String[] arr = tables.toArray(new String[0]);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.drop_which)
                .setItems(arr, (d, which) -> confirm(fmt(R.string.drop_title, arr[which]),
                        getString(R.string.drop_msg),
                        () -> runDdl("DROP TABLE " + q(arr[which]))))
                .show();
    }

    // alter table: add / rename / drop column, rename table

    private void alterTableDialog() {
        List<String> tl = DbHelper.getTablesOnly(db);
        if (tl.isEmpty()) {
            toast(getString(R.string.no_tables));
            return;
        }
        final String[] tables = tl.toArray(new String[0]);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.choose_table)
                .setItems(tables, (d, i) -> alterActions(tables[i]))
                .show();
    }

    private void alterActions(final String t) {
        String[] acts = {
                getString(R.string.act_add_column),
                getString(R.string.act_rename_column),
                getString(R.string.act_drop_column),
                getString(R.string.act_rename_table)
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(t)
                .setItems(acts, (d, w) -> {
                    if (w == 0) {
                        addColumnDialog(t);
                    } else if (w == 1) {
                        pickColumn(t, getString(R.string.rename_which), c ->
                                prompt(fmt(R.string.new_name_for, c), c, s ->
                                        runDdl("ALTER TABLE " + q(t) + " RENAME COLUMN " + q(c) + " TO " + q(s))));
                    } else if (w == 2) {
                        pickColumn(t, getString(R.string.drop_col_which), c ->
                                confirm(fmt(R.string.drop_col_title, c), getString(R.string.drop_col_msg), () ->
                                        runDdl("ALTER TABLE " + q(t) + " DROP COLUMN " + q(c))));
                    } else {
                        prompt(getString(R.string.new_table_name), t, s ->
                                runDdl("ALTER TABLE " + q(t) + " RENAME TO " + q(s)));
                    }
                })
                .show();
    }

    private void pickColumn(String table, String title, Str cb) {
        final String[] cols = DbHelper.getColumns(db, table).toArray(new String[0]);
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setItems(cols, (d, i) -> cb.run(cols[i]))
                .show();
    }

    private void addColumnDialog(final String table) {
        LinearLayout ll = vbox();
        final EditText etName = new EditText(this);
        etName.setHint(R.string.hint_col_name);
        etName.setSingleLine(true);
        final Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, TYPES));
        final EditText etDef = new EditText(this);
        etDef.setHint(R.string.hint_default);
        etDef.setSingleLine(true);
        ll.addView(etName);
        ll.addView(sp);
        ll.addView(etDef);

        new MaterialAlertDialogBuilder(this)
                .setTitle(fmt(R.string.add_col_title, table))
                .setView(ll)
                .setPositiveButton(R.string.add, (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) return;
                    String sql = "ALTER TABLE " + q(table) + " ADD COLUMN " + q(name) + " " + sp.getSelectedItem();
                    String def = etDef.getText().toString();
                    if (!def.isEmpty()) sql += " DEFAULT " + DatabaseUtils.sqlEscapeString(def);
                    runDdl(sql);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // index manager

    private void indexManagerDialog() {
        final List<String[]> idx = DbHelper.getIndexes(db);
        String[] items = new String[idx.size()];
        for (int i = 0; i < items.length; i++) items[i] = idx.get(i)[0] + "   ·   " + idx.get(i)[1];

        AlertDialog.Builder b = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.btn_indexes)
                .setPositiveButton(R.string.new_index, (d, w) -> newIndexDialog())
                .setNegativeButton(R.string.close, null);
        if (items.length == 0) {
            b.setMessage(R.string.no_indexes);
        } else {
            b.setItems(items, (d, which) -> {
                final String[] it = idx.get(which);
                new MaterialAlertDialogBuilder(this)
                        .setTitle(it[0])
                        .setMessage(it[2])
                        .setPositiveButton(R.string.drop_index, (d2, w2) -> runDdl("DROP INDEX " + q(it[0])))
                        .setNegativeButton(R.string.close, null)
                        .show();
            });
        }
        b.show();
    }

    private void newIndexDialog() {
        List<String> tl = DbHelper.getTablesOnly(db);
        if (tl.isEmpty()) {
            toast(getString(R.string.no_tables));
            return;
        }
        LinearLayout ll = vbox();
        final Spinner spT = new Spinner(this);
        spT.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, tl));

        final EditText etCols = new EditText(this);
        etCols.setHint(R.string.hint_index_cols);
        etCols.setSingleLine(true);

        final EditText etName = new EditText(this);
        etName.setHint(R.string.hint_index_name);
        etName.setSingleLine(true);

        final CheckBox cbUnique = new CheckBox(this);
        cbUnique.setText("UNIQUE");
        ll.addView(spT);
        ll.addView(etCols);
        ll.addView(etName);
        ll.addView(cbUnique);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.new_index)
                .setView(ll)
                .setPositiveButton(R.string.create, (d, w) -> {
                    String table = spT.getSelectedItem().toString();
                    String raw = etCols.getText().toString().trim();
                    if (raw.isEmpty()) {
                        toast(getString(R.string.err_index_cols));
                        return;
                    }
                    StringBuilder cols = new StringBuilder();
                    for (String c : raw.split(",")) {
                        c = c.trim();
                        if (c.isEmpty()) continue;
                        if (cols.length() > 0) cols.append(", ");
                        cols.append(q(c));
                    }
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = "idx_" + table + "_" + raw.replaceAll("[^\\p{L}\\p{N}]+", "_");
                    }
                    runDdl("CREATE " + (cbUnique.isChecked() ? "UNIQUE " : "") + "INDEX " + q(name)
                            + " ON " + q(table) + " (" + cols + ")");
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // SQL TAB

    private void runSql() {
        String sql = etSql.getText().toString();
        if (sql.trim().isEmpty()) return;
        try {
            final QueryResult r = DbHelper.execute(db, sql);

            StringBuilder status = new StringBuilder(fmt(R.string.sql_status, r.statements, r.millis));
            if (r.columns.length > 0) {
                status.append(" • ").append(fmt(R.string.sql_rows, r.rows.size()));
                if (r.rows.size() >= DbHelper.MAX_ROWS) {
                    status.append(' ').append(fmt(R.string.sql_limited, DbHelper.MAX_ROWS));
                }
            }
            tvStatus.setText(status.toString());

            if (r.columns.length > 0) bindGrid(headerSql, rvSql, r, pos -> showRowDialog(r, pos));
            else clearGrid(headerSql, rvSql);
            refresh();
        } catch (Exception e) {
            tvStatus.setText(fmt(R.string.err_generic, e.getMessage()));
            clearGrid(headerSql, rvSql);
        }
    }

    private void showRowDialog(QueryResult r, int pos) {
        StringBuilder sb = new StringBuilder();
        String[] row = r.rows.get(pos);
        for (int i = 0; i < r.columns.length; i++) {
            sb.append(r.columns[i]).append(": ").append(row[i]).append("\n\n");
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(fmt(R.string.row_title, pos + 1))
                .setMessage(sb.toString().trim())
                .setPositiveButton(R.string.close, null)
                .show();
    }
}