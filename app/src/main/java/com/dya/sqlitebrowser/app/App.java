package com.dya.sqlitebrowser.app;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

import com.dya.sqlitebrowser.R;
import com.dya.sqlitebrowser.Util.LocaleHelper;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class App extends Application {

    private static final String PREFS = "settings";
    private static final String KEY = "night";

    @Override
    public void onCreate() {
        super.onCreate();
        int mode = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(KEY, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        AppCompatDelegate.setDefaultNightMode(mode);

        LocaleHelper.apply(this); // English by default
    }

    /** Shows the System / Light / Dark chooser */
    public static void chooseTheme(Activity a) {
        final SharedPreferences sp = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final int[] modes = {
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                AppCompatDelegate.MODE_NIGHT_NO,
                AppCompatDelegate.MODE_NIGHT_YES
        };
        int cur = sp.getInt(KEY, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        int sel = 0;
        for (int i = 0; i < modes.length; i++) if (modes[i] == cur) sel = i;

        String[] names = {
                a.getString(R.string.theme_system),
                a.getString(R.string.theme_light),
                a.getString(R.string.theme_dark)
        };

        new MaterialAlertDialogBuilder(a)
                .setTitle(R.string.theme_title)
                .setSingleChoiceItems(names, sel, (d, which) -> {
                    sp.edit().putInt(KEY, modes[which]).apply();
                    d.dismiss();
                    AppCompatDelegate.setDefaultNightMode(modes[which]);
                })
                .show();
    }
}