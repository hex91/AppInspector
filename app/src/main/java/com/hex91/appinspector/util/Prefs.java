package com.hex91.appinspector.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/** 主题设置持久化 */
public class Prefs {

    public static final int MODE_FOLLOW_SYSTEM = 0;
    public static final int MODE_DAY = 1;
    public static final int MODE_NIGHT = 2;

    private static final String SP = "appinspector_prefs";
    private static final String KEY_THEME = "theme_mode";

    public static int themeMode(Context ctx) {
        return ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
                .getInt(KEY_THEME, MODE_FOLLOW_SYSTEM);
    }

    public static void setThemeMode(Context ctx, int mode) {
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
                .edit().putInt(KEY_THEME, mode).apply();
    }

    /** 在 Activity setContentView 之前调用 */
    public static void applyTheme(Context ctx) {
        int mode;
        switch (themeMode(ctx)) {
            case MODE_DAY: mode = AppCompatDelegate.MODE_NIGHT_NO; break;
            case MODE_NIGHT: mode = AppCompatDelegate.MODE_NIGHT_YES; break;
            default: mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM; break;
        }
        AppCompatDelegate.setDefaultNightMode(mode);
    }
}
