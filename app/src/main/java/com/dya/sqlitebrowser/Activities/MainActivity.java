package com.dya.sqlitebrowser.Activities;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.dya.sqlitebrowser.Database.DbHelper;
import com.dya.sqlitebrowser.R;
import com.dya.sqlitebrowser.Util.JsonImporter;
import com.dya.sqlitebrowser.Util.LocaleHelper;
import com.dya.sqlitebrowser.app.App;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONException;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private File dir;
    private final List<File> files = new ArrayList<>();
    private ArrayAdapter<File> adapter;
    private File pendingExport;
    private ActivityResultLauncher<String[]> importLauncher;
    private ActivityResultLauncher<String[]> jsonLauncher;
    private ActivityResultLauncher<String> exportLauncher;

    ListView list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        EdgeToEdge.enable(this);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        setTitle(R.string.app_title);

        dir = new File(getFilesDir(), "dbs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();

        importLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) importDb(uri, false);
        });
        jsonLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) jsonToDbDialog(uri);
        });
        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/octet-stream"), uri -> {
                    if (uri != null && pendingExport != null) exportDb(uri);
                });

        list = findViewById(R.id.list);
        list.setEmptyView(findViewById(R.id.empty));

        adapter = new ArrayAdapter<File>(this, android.R.layout.simple_list_item_2, android.R.id.text1, files) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                File f = getItem(position);
                TextView title = v.findViewById(android.R.id.text1);
                TextView subtitle = v.findViewById(android.R.id.text2);

                Typeface font = androidx.core.content.res.ResourcesCompat.getFont(
                        MainActivity.this,
                        R.font.charis_sil_bold
                );

                int textColor = MaterialColors.getColor(
                        v,
                        com.google.android.material.R.attr.colorOnSurface
                );

                int secondaryTextColor = MaterialColors.getColor(
                        v,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                        textColor
                );

                assert f != null;
                title.setText(f.getName());
                title.setTypeface(font);
                title.setTextColor(textColor);

                subtitle.setText(
                        Formatter.formatShortFileSize(MainActivity.this, f.length())
                );
                subtitle.setTypeface(font);
                subtitle.setTextColor(secondaryTextColor);

                return v;
            }
        };
        list.setAdapter(adapter);

        list.setOnItemClickListener((p, v, pos, id) -> openDb(files.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            showFileMenu(files.get(pos));
            return true;
        });

        findViewById(R.id.btnNew).setOnClickListener(v -> newDbDialog());
        findViewById(R.id.btnImport).setOnClickListener(v -> showImportChooser());

        if (savedInstanceState == null) handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    /** A .db file opened from the file manager (or any other app) */
    private void handleIntent(Intent i) {
        if (i != null && Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            Uri u = i.getData();
            setIntent(new Intent(this, MainActivity.class));
            importDb(u, true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    //helpers

    private String fmt(int res, Object... args) {
        return String.format(Locale.US, getString(res), args);
    }

    private void toast(String s) {
        @SuppressLint("InflateParams") View view = LayoutInflater.from(this)
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

    @SuppressLint("RestrictedApi")
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_custom_menu) {
            showAppMenu();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void reload() {
        files.clear();
        File[] arr = dir.listFiles();
        if (arr != null) {
            Arrays.sort(arr, Comparator.comparing(f -> f.getName().toLowerCase()));
            for (File f : arr) {
                String n = f.getName();
                if (!n.endsWith("-journal") && !n.endsWith("-wal") && !n.endsWith("-shm")) files.add(f);
            }
        }
        adapter.notifyDataSetChanged();
    }

    //app menu

    private void showAppMenu() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);

        @SuppressLint("InflateParams") View view = getLayoutInflater()
                .inflate(R.layout.bottom_sheet_file_menu, null);

        view.setBackground(
                ContextCompat.getDrawable(this, R.drawable.bg_bottom_sheet)
        );

        dialog.setContentView(view);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(
                    android.R.color.transparent
            );
        }

        view.findViewById(R.id.actionTheme).setOnClickListener(v -> {
            dialog.dismiss();
            App.chooseTheme(this);
        });

        view.findViewById(R.id.actionLanguage).setOnClickListener(v -> {
            dialog.dismiss();
            LocaleHelper.choose(this);
        });

        view.findViewById(R.id.actionReview).setOnClickListener(v -> {
            dialog.dismiss();

            String url = "https://play.google.com/store/apps/details?id="
                    + getPackageName();

            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        });

        view.findViewById(R.id.actionShare).setOnClickListener(v -> {
            dialog.dismiss();

            String url = "https://play.google.com/store/apps/details?id="
                    + getPackageName();

            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, getString(R.string.share_text) + "\n\n" + url);

            startActivity(Intent.createChooser(share, getString(R.string.share_chooser)));
        });

        view.findViewById(R.id.actionTelegram).setOnClickListener(v -> {
            dialog.dismiss();

            String url = "https://t.me/wallacoding";
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        });

        dialog.show();
    }

    private void openDb(File f) {
        Intent i = new Intent(this, DbActivity.class);
        i.putExtra("path", f.getAbsolutePath());
        startActivity(i);
    }

    // create database

    /** Create a database from the UI (name) and/or from code (optional SQL script) */
    private void newDbDialog() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(20), dp(8), dp(20), 0);

        final EditText etName = new EditText(this);
        etName.setHint(R.string.new_db_name_hint);
        etName.setSingleLine(true);

        final EditText etSql = new EditText(this);
        etSql.setHint(getString(R.string.new_db_sql_hint)
                + "\nCREATE TABLE users(\n  id INTEGER PRIMARY KEY,\n  name TEXT\n);\nINSERT INTO users(name) VALUES('Ali');");
        etSql.setMinLines(6);
        etSql.setGravity(android.view.Gravity.TOP);
        etSql.setTypeface(Typeface.MONOSPACE);
        etSql.setTextSize(13);

        ll.addView(etName);
        ll.addView(etSql);

        final AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.new_database)
                .setView(ll)
                .setPositiveButton(R.string.create, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dlg.show();

        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            if (name.isEmpty()) {
                etName.setError(getString(R.string.err_enter_name));
                return;
            }
            if (!name.contains(".")) name += ".db";
            File f = new File(dir, name);
            if (f.exists()) {
                etName.setError(getString(R.string.err_file_exists));
                return;
            }
            String sql = etSql.getText().toString();
            try {
                SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(f, null);
                try {
                    if (!sql.trim().isEmpty()) {
                        db.beginTransaction();
                        try {
                            DbHelper.execute(db, sql);
                            db.setTransactionSuccessful();
                        } finally {
                            db.endTransaction();
                        }
                    }
                } finally {
                    db.close();
                }
                dlg.dismiss();
                reload();
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                etSql.setError(fmt(R.string.err_sql, e.getMessage()));
            }
        });
    }

    //file actions

    private void showFileMenu(File f) {
        String[] items = {
                getString(R.string.open),
                getString(R.string.file_export),
                getString(R.string.delete)
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(f.getName())
                .setItems(items, (d, which) -> {
                    if (which == 0) openDb(f);
                    else if (which == 1) {
                        pendingExport = f;
                        exportLauncher.launch(f.getName());
                    } else confirmDelete(f);
                })
                .show();
    }

    private void confirmDelete(File f) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_db_title)
                .setMessage(fmt(R.string.delete_db_msg, f.getName()))
                .setPositiveButton(R.string.delete, (d, w) -> {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    new File(f.getPath() + "-journal").delete();
                    reload();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    //import database

    private void showImportChooser() {
        String[] items = {
                getString(R.string.import_opt_db),
                getString(R.string.import_opt_json)
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.import_label)
                .setItems(items, (d, which) -> {
                    if (which == 0) importLauncher.launch(new String[]{"*/*"});
                    else jsonLauncher.launch(new String[]{"*/*"});
                })
                .show();
    }

    /** Copy a database into the app folder. If open == true, open it_afterwards. */
    private void importDb(Uri uri, boolean open) {
        String name = "imported.db";
        String seg = uri.getLastPathSegment();
        if (seg != null && !seg.isEmpty()) name = seg.substring(seg.lastIndexOf('/') + 1);
        try {
            Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                if (c.moveToFirst()) {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0 && c.getString(idx) != null) name = c.getString(idx);
                }
                c.close();
            }
        } catch (Exception ignored) {
        }
        File out = new File(dir, name);
        int n = 1;
        while (out.exists()) {
            out = new File(dir, "copy" + (n++) + "_" + name);
        }
        final File target = out;
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream os = new java.io.FileOutputStream(target)) {
                copy(in, os);
                runOnUiThread(() -> {
                    reload();
                    if (open) openDb(target);
                    else toast(fmt(R.string.imported_file, target.getName()));
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast(fmt(R.string.import_failed, e.getMessage())));
            }
        }).start();
    }

    //JSON -> new database

    private void jsonToDbDialog(final Uri uri) {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(20), dp(8), dp(20), 0);

        final EditText etName = new EditText(this);
        etName.setSingleLine(true);
        etName.setText(JsonImporter.baseName(uri));
        etName.setSelection(etName.getText().length());
        ll.addView(etName);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.json_title)
                .setMessage(R.string.json_msg)
                .setView(ll)
                .setPositiveButton(R.string.json_convert, (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) return;
                    convertJson(uri, name);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void convertJson(final Uri uri, String rawName) {
        String name = rawName;
        if (!name.contains(".")) name += ".db";
        final File f = new File(dir, name);
        if (f.exists()) {
            toast(getString(R.string.err_file_exists));
            return;
        }
        final String tableName = JsonImporter.baseName(uri);

        new Thread(() -> {
            SQLiteDatabase d = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                String json = JsonImporter.readAll(in);
                d = SQLiteDatabase.openOrCreateDatabase(f, null);
                final JsonImporter.Result r = JsonImporter.importInto(d, json, tableName);
                d.close();
                d = null;

                if (r.tables.isEmpty()) {
                    discard(f);
                    runOnUiThread(() -> toast(getString(R.string.json_nothing)));
                    return;
                }
                runOnUiThread(() -> {
                    reload();
                    toast(fmt(R.string.json_done_new, f.getName(), r.tables.size(), r.rows));
                    openDb(f);
                });
            } catch (OutOfMemoryError oom) {
                if (d != null) d.close();
                discard(f);
                runOnUiThread(() -> toast(getString(R.string.json_too_big)));
            } catch (JSONException je) {
                if (d != null) d.close();
                discard(f);
                runOnUiThread(() -> toast(fmt(R.string.json_invalid, je.getMessage())));
            } catch (Exception e) {
                if (d != null) d.close();
                discard(f);
                runOnUiThread(() -> toast(fmt(R.string.err_generic, e.getMessage())));
            }
        }).start();
    }

    private void discard(File f) {
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        new File(f.getPath() + "-journal").delete();
        new File(f.getPath() + "-wal").delete();
        new File(f.getPath() + "-shm").delete();
    }

    // export database

    private void exportDb(Uri uri) {
        final File src = pendingExport;
        new Thread(() -> {
            try (InputStream in = new java.io.FileInputStream(src);
                 OutputStream os = getContentResolver().openOutputStream(uri)) {
                copy(in, os);
                runOnUiThread(() -> toast(getString(R.string.exported)));
            } catch (Exception e) {
                runOnUiThread(() -> toast(fmt(R.string.export_failed, e.getMessage())));
            }
        }).start();
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int len;
        while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        out.flush();
    }
}