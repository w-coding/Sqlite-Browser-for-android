package com.dya.sqlitebrowser.Util;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.os.Build;
import android.os.LocaleList;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.dya.sqlitebrowser.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class LocaleHelper {

    private static final String PREFS = "settings";
    private static final String KEY = "lang";

    /** English is the default */
    public static final String[] TAGS = {"en", "ar", "ckb"};
    public static final String[] NAMES = {"English", "العربية", "کوردی (سۆرانی)"};

    public static String saved(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, TAGS[0]);
    }

    /** Applies the saved language. Call from Application.onCreate and after changing it. */
    public static void apply(Context c) {
        String tag = saved(c);
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleManager lm = c.getSystemService(LocaleManager.class);
            if (lm != null) lm.setApplicationLocales(LocaleList.forLanguageTags(tag));
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag));
        }
    }

    /** Language chooser dialog */
    public static void choose(Activity a) {
        String cur = saved(a);
        int sel = 0;
        for (int i = 0; i < TAGS.length; i++) if (TAGS[i].equals(cur)) sel = i;

        new MaterialAlertDialogBuilder(a)
                .setTitle(R.string.menu_language)
                .setSingleChoiceItems(NAMES, sel, (d, which) -> {
                    a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putString(KEY, TAGS[which]).apply();
                    d.dismiss();
                    apply(a);
                })
                .show();
    }
}