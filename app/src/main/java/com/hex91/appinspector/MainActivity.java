package com.hex91.appinspector;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.hex91.appinspector.adapter.AppAdapter;
import com.hex91.appinspector.model.AppInfo;
import com.hex91.appinspector.util.ApkExporter;
import com.hex91.appinspector.util.IconCache;
import com.hex91.appinspector.util.PkgScanner;
import com.hex91.appinspector.util.Prefs;
import com.hex91.appinspector.util.RootShell;
import com.hex91.appinspector.util.Uninstaller;
import com.hex91.appinspector.util.XapkExporter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 主界面：列出所有已安装应用 */
public class MainActivity extends AppCompatActivity {

    private final List<AppInfo> allApps = new ArrayList<>();
    private final List<AppInfo> shown = new ArrayList<>();
    private AppAdapter adapter;
    private EditText etSearch;
    private ProgressBar progress;
    private TextView tvEmpty, tvCount;
    private SwipeRefreshLayout swipe;

    private int filter = 0; // 0 全部 1 三方 2 系统
    private String query = "";
    private boolean rootOk = false;
    private int appliedTheme = -1;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        appliedTheme = Prefs.themeMode(this);
        Prefs.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        etSearch = findViewById(R.id.etSearch);
        progress = findViewById(R.id.progress);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvCount = findViewById(R.id.tvCount);
        swipe = findViewById(R.id.swipe);
        RecyclerView rv = findViewById(R.id.rvApps);

        adapter = new AppAdapter(this);
        adapter.setListener(new AppAdapter.OnItemClickListener() {
            @Override
            public void onClick(AppInfo info) {
                Intent i = new Intent(MainActivity.this, ComponentsActivity.class);
                i.putExtra(ComponentsActivity.EXTRA_PKG, info.packageName);
                i.putExtra(ComponentsActivity.EXTRA_LABEL, info.label);
                startActivity(i);
            }

            @Override
            public boolean onLongClick(final AppInfo info) {
                showAppOps(info);
                return true;
            }
        });
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
        rv.addItemDecoration(new androidx.recyclerview.widget.DividerItemDecoration(
                this, androidx.recyclerview.widget.DividerItemDecoration.VERTICAL));

        RadioGroup rg = findViewById(R.id.rgFilter);
        rg.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                if (checkedId == R.id.rbUser) filter = 1;
                else if (checkedId == R.id.rbSystem) filter = 2;
                else filter = 0;
                applyFilter();
            }
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                query = s == null ? "" : s.toString().trim().toLowerCase();
                applyFilter();
            }
        });

        swipe.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                PkgScanner.clearCache();
                loadApps();
            }
        });

        toolbar.inflateMenu(R.menu.menu_main);
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_root) {
                checkRoot();
                return true;
            } else if (id == R.id.action_top) {
                showTopActivity();
                return true;
            } else if (id == R.id.action_restore) {
                Uninstaller.showRestore(MainActivity.this);
                return true;
            } else if (id == R.id.action_files) {
                startActivity(new Intent(MainActivity.this, FileBrowserActivity.class));
                return true;
            } else if (id == R.id.action_theme) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                return true;
            }
            return false;
        });

        checkRoot();
        loadApps();
    }

    private void checkRoot() {
        executor.execute(() -> {
            final boolean ok = RootShell.hasRoot();
            runOnUiThread(() -> {
                rootOk = ok;
                MaterialToolbar tb = findViewById(R.id.toolbar);
                tb.setSubtitle(ok ? "Root 已授权" : "无 Root（仅能查看导出组件）");
                tb.setSubtitleTextColor(getResources().getColor(
                        ok ? R.color.green : R.color.red, getTheme()));
            });
        });
    }

    private void loadApps() {
        progress.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);
        executor.execute(() -> {
            final List<AppInfo> list = PkgScanner.scanApps(MainActivity.this);
            runOnUiThread(() -> {
                allApps.clear();
                allApps.addAll(list);
                applyFilter();
                progress.setVisibility(View.GONE);
                swipe.setRefreshing(false);
                warmIcons();
            });
        });
    }

    /** 后台预生成图标，边生成边刷新列表 */
    private void warmIcons() {
        executor.execute(() -> {
            int n = allApps.size();
            for (int i = 0; i < n; i++) {
                IconCache.warmUp(MainActivity.this, allApps.get(i).packageName);
                if (i % 15 == 0) {
                    handler.post(() -> adapter.notifyDataSetChanged());
                }
            }
            handler.post(() -> adapter.notifyDataSetChanged());
        });
    }

    private void applyFilter() {
        shown.clear();
        for (AppInfo a : allApps) {
            if (filter == 1 && a.isSystem) continue;
            if (filter == 2 && !a.isSystem) continue;
            if (!query.isEmpty() && !a.lowerKey().contains(query)) continue;
            shown.add(a);
        }
        adapter.setData(shown);
        tvEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        tvCount.setText("共 " + shown.size() + " 个应用"
                + (rootOk ? "  ·  Root 已授权" : "  ·  未获取 Root"));
    }

    private void showAppOps(final AppInfo info) {
        String[] items = {
                "查看组件（四大组件）",
                "复制包名",
                "系统应用信息页",
                "Root：强制停止",
                "Root：清除数据",
                "应用详情",
                "卸载 / 移除应用",
                "提取安装包 (APK) 到 Download",
                "导出为 XAPK（合并 split 安装包）"
        };
        new AlertDialog.Builder(this)
                .setTitle(info.label)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            Intent i = new Intent(MainActivity.this, ComponentsActivity.class);
                            i.putExtra(ComponentsActivity.EXTRA_PKG, info.packageName);
                            i.putExtra(ComponentsActivity.EXTRA_LABEL, info.label);
                            startActivity(i);
                            break;
                        case 1:
                            copy(info.packageName);
                            break;
                        case 2:
                            Intent d = new Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + info.packageName));
                            startActivity(d);
                            break;
                        case 3:
                            runRoot("am force-stop " + info.packageName);
                            break;
                        case 4:
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("确认")
                                    .setMessage("将清除 " + info.label + " 的全部数据（等同于恢复安装），继续？")
                                    .setPositiveButton("清除", (dd, w) ->
                                            runRoot("pm clear " + info.packageName))
                                    .setNegativeButton("取消", null)
                                    .show();
                            break;
                        case 5:
                            StringBuilder sb = new StringBuilder();
                            sb.append("应用名：").append(info.label).append("\n");
                            sb.append("包名：").append(info.packageName).append("\n");
                            sb.append("版本：").append(info.versionName)
                              .append(" (").append(info.versionCode).append(")\n");
                            sb.append("targetSdk：").append(info.targetSdk).append("\n");
                            sb.append("minSdk：").append(info.minSdk).append("\n");
                            sb.append("UID：").append(info.uid).append("\n");
                            sb.append("类型：").append(info.isSystem ? "系统应用" : "三方应用").append("\n");
                            sb.append("状态：").append(info.enabled ? "启用" : "已停用").append("\n");
                            sb.append("APK：").append(info.sourceDir).append("\n");
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("详情")
                                    .setMessage(sb.toString())
                                    .setPositiveButton("关闭", null)
                                    .setNeutralButton("复制", (dd, w) -> copy(sb.toString()))
                                    .show();
                            break;
                        case 6:
                            Uninstaller.show(MainActivity.this, info, new Runnable() {
                                @Override
                                public void run() {
                                    PkgScanner.clearCache();
                                    loadApps();
                                }
                            });
                            break;
                case 7:
                    ApkExporter.export(MainActivity.this, info, null);
                    break;
                case 8:
                    XapkExporter.export(MainActivity.this, info, null);
                    break;
                    }
                })
                .show();
    }

    private void runRoot(final String cmd) {
        executor.execute(() -> {
            final RootShell.Result r = RootShell.run(cmd);
            runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                    .setTitle(r.ok() ? "执行完成" : "执行返回错误")
                    .setMessage("$ " + cmd + "\n\n" + r.message())
                    .setPositiveButton("关闭", null)
                    .show());
        });
    }

    /** 读取当前栈顶 Activity，研判时很有用 */
    private void showTopActivity() {
        executor.execute(() -> {
            String out;
            RootShell.Result r1 = RootShell.run("cmd activity get-top-activity", 10);
            if (r1.ok() && r1.stdout.contains("/")) {
                out = r1.stdout.trim();
            } else {
                RootShell.Result r2 = RootShell.run(
                        "dumpsys activity activities | grep -Ei 'topResumedActivity|mResumedActivity' | head -5", 15);
                out = r2.stdout.trim();
                if (out.isEmpty()) out = r2.message();
            }
            if (!out.isEmpty()) out += "\n\n（切换到其它 App 几秒后再点此菜单，可看到对方的界面）";
            final String msg = out;
            runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                    .setTitle("当前前台界面")
                    .setMessage(msg.isEmpty() ? "读取失败" : msg)
                    .setPositiveButton("关闭", null)
                    .setNeutralButton("复制", (d, w) -> copy(msg))
                    .show());
        });
    }

    /** 回到前台时静默刷新，用于反映常规卸载/安装的变化 */
    @Override
    protected void onResume() {
        super.onResume();
        // 主题设置页修改后返回时立即生效（AppCompat 一般会自动重建，这里兜底）
        if (Prefs.themeMode(this) != appliedTheme) {
            appliedTheme = Prefs.themeMode(this);
            recreate();
            return;
        }
        if (allApps.isEmpty()) return;
        executor.execute(() -> {
            final List<AppInfo> list = PkgScanner.scanApps(MainActivity.this);
            runOnUiThread(() -> {
                allApps.clear();
                allApps.addAll(list);
                applyFilter();
            });
        });
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("text", text));
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
