package com.hex91.appinspector;

import android.os.Bundle;
import android.widget.RadioGroup;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.hex91.appinspector.util.Prefs;

/** 主题设置：跟随系统 / 日间 / 夜间 */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Prefs.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("主题设置");
        toolbar.setNavigationOnClickListener(v -> finish());

        final RadioGroup rg = findViewById(R.id.rgTheme);
        int cur = Prefs.themeMode(this);
        rg.check(rg.getChildAt(cur).getId());

        rg.setOnCheckedChangeListener((group, checkedId) -> {
            int mode = rg.indexOfChild(rg.findViewById(checkedId));
            if (mode == Prefs.themeMode(this)) return;
            Prefs.setThemeMode(this, mode);
            Prefs.applyTheme(this);
            recreate();
        });
    }
}
