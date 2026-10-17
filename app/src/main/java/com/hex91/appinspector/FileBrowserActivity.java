package com.hex91.appinspector;

import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.Formatter;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.hex91.appinspector.adapter.FileAdapter;
import com.hex91.appinspector.util.ApkInstaller;
import com.hex91.appinspector.util.FileLister;
import com.hex91.appinspector.util.Prefs;
import com.hex91.appinspector.util.RootShell;
import com.hex91.appinspector.util.SplitArchive;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 文件浏览器：浏览系统目录（有 Root 可看 /data、/system），
 * 点击 .apk 直接安装，点击 .apks/.apkm/.xapk 解析后合并安装。
 */
public class FileBrowserActivity extends AppCompatActivity
        implements FileAdapter.OnFileClickListener {

    private MaterialToolbar toolbar;
    private TextView tvPath, tvEmpty;
    private ProgressBar progress;
    private RecyclerView rv;
    private FileAdapter adapter;

    private final List<FileLister.Item> items = new ArrayList<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    private String currentPath = "/sdcard";
    private boolean rootOk = false;
    private int appliedTheme = -1;

    private static final String[] PRESETS = {
            "/sdcard",
            "/sdcard/Download",
            "/sdcard/Documents",
            "/data/local/tmp",
            "/data/app",
            "/data/data",
            "/system/app",
            "/system/priv-app",
            "/system/framework",
            "/"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        appliedTheme = Prefs.themeMode(this);
        Prefs.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_browser);

        toolbar = findViewById(R.id.toolbar);
        tvPath = findViewById(R.id.tvPath);
        tvEmpty = findViewById(R.id.tvEmpty);
        progress = findViewById(R.id.progress);
        rv = findViewById(R.id.rvFiles);

        toolbar.setNavigationOnClickListener(v -> goUp());
        toolbar.inflateMenu(R.menu.menu_files);
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_jump) {
                showJumpDialog();
                return true;
            } else if (id == R.id.action_preset) {
                showPresetDialog();
                return true;
            } else if (id == R.id.action_refresh) {
                load(currentPath);
                return true;
            }
            return false;
        });

        adapter = new FileAdapter(this, this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        executor.execute(() -> {
            rootOk = RootShell.hasRoot();
            runOnUiThread(() -> {
                toolbar.setSubtitle(rootOk
                        ? "Root 已授权 · 可浏览 /data /system"
                        : "无 Root · 仅能浏览公共目录");
                toolbar.setSubtitleTextColor(getResources().getColor(
                        rootOk ? R.color.green : R.color.red, getTheme()));
            });
        });

        load("/sdcard");
    }

    // ---------- 目录加载 ----------

    private void load(final String path) {
        if (path == null || path.isEmpty()) return;
        currentPath = path;
        tvPath.setText(path);
        progress.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);
        executor.execute(() -> {
            final List<FileLister.Item> list = FileLister.list(path, rootOk);
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                items.clear();
                if (list == null) {
                    tvEmpty.setVisibility(View.VISIBLE);
                    tvEmpty.setText(rootOk ? "无法访问该目录" : "无法访问该目录（可能需要 Root）");
                    adapter.setData(items);
                    return;
                }
                if (!path.equals("/")) {
                    FileLister.Item up = new FileLister.Item();
                    up.name = "..";
                    up.dir = true;
                    up.path = parentOf(path);
                    items.add(up);
                }
                items.addAll(list);
                tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                if (items.isEmpty()) tvEmpty.setText("空目录");
                adapter.setData(items);
            });
        });
    }

    private void goUp() {
        String parent = parentOf(currentPath);
        if (parent == null) {
            finish();
            return;
        }
        load(parent);
    }

    private static String parentOf(String path) {
        if (path == null || path.equals("/")) return null;
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int idx = p.lastIndexOf('/');
        if (idx <= 0) return "/";
        return p.substring(0, idx);
    }

    // ---------- 点击 / 长按 ----------

    @Override
    public void onClick(FileLister.Item it) {
        if (it.dir) {
            load(it.path);
            return;
        }
        String lower = it.name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".apk")) {
            List<String> one = new ArrayList<>();
            one.add(it.path);
            ApkInstaller.install(this, one, it.name);
        } else if (SplitArchive.isSplitArchive(it.name)) {
            parseSplitArchive(it.path);
        } else {
            Toast.makeText(this,
                    "仅支持安装 .apk / .apks / .apkm / .xapk 文件", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onLongClick(FileLister.Item it) {
        if (it.name.equals("..")) {
            goUp();
            return;
        }
        String[] ops = {"安装 / 打开", "复制路径", "文件信息"};
        new AlertDialog.Builder(this)
                .setTitle(it.name)
                .setItems(ops, (d, w) -> {
                    if (w == 0) {
                        onClick(it);
                    } else if (w == 1) {
                        copy(it.path);
                    } else {
                        showInfo(it);
                    }
                })
                .show();
    }

    // ---------- 拆分安装包解析 ----------

    private void parseSplitArchive(final String path) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在解析 " + new File(path).getName() + " …");
        pd.setCancelable(false);
        pd.show();
        executor.execute(() -> {
            try {
                final SplitArchive.Parsed p = SplitArchive.parse(this, path);
                runOnUiThread(() -> {
                    pd.dismiss();
                    showParsedDialog(p);
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle("解析失败")
                            .setMessage(e.getMessage() == null ? e.toString() : e.getMessage())
                            .setPositiveButton("关闭", null)
                            .show();
                });
            }
        });
    }

    private void showParsedDialog(final SplitArchive.Parsed p) {
        StringBuilder sb = new StringBuilder();
        sb.append("文件：").append(new File(p.file).getName()).append("\n");
        if (p.packageName != null && !p.packageName.isEmpty()) {
            sb.append("包名：").append(p.packageName).append("\n");
        }
        if (p.versionName != null && !p.versionName.isEmpty()) {
            sb.append("版本：").append(p.versionName).append("\n");
        }
        sb.append("\n包含 ").append(p.apks.size()).append(" 个 APK：\n");
        for (String n : p.names) sb.append("· ").append(n).append("\n");
        new AlertDialog.Builder(this)
                .setTitle("拆分安装包")
                .setMessage(sb.toString())
                .setNegativeButton("取消", null)
                .setNeutralButton("复制命令", (d, w) ->
                        copy(ApkInstaller.buildCmd(p.apks)))
                .setPositiveButton("安装", (d, w) ->
                        ApkInstaller.install(this, p.apks, p.packageName))
                .show();
    }

    private void showInfo(FileLister.Item it) {
        StringBuilder sb = new StringBuilder();
        sb.append("名称：").append(it.name).append("\n");
        sb.append("路径：").append(it.path).append("\n");
        if (it.dir) {
            sb.append("类型：目录\n");
        } else {
            sb.append("大小：").append(Formatter.formatFileSize(this, it.size))
              .append("（").append(it.size).append(" B）\n");
        }
        if (it.perms != null) sb.append("权限：").append(it.perms).append("\n");
        if (it.mtime > 0) {
            sb.append("修改时间：").append(new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(it.mtime)));
        }
        new AlertDialog.Builder(this)
                .setTitle("文件信息")
                .setMessage(sb.toString())
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制", (d, w) -> copy(sb.toString()))
                .show();
    }

    // ---------- 路径跳转 / 预设 ----------

    private void showJumpDialog() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setText(currentPath);
        et.setSelection(et.getText().length());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(32, 16, 32, 0);
        box.addView(et);
        new AlertDialog.Builder(this)
                .setTitle("跳转路径")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("前往", (d, w) -> {
                    String p = et.getText().toString().trim();
                    if (!p.isEmpty()) load(p);
                })
                .show();
    }

    private void showPresetDialog() {
        new AlertDialog.Builder(this)
                .setTitle("预设目录")
                .setItems(PRESETS, (d, w) -> load(PRESETS[w]))
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- 主题 / 生命周期 ----------

    @Override
    protected void onResume() {
        super.onResume();
        // 设置页改完主题返回时立即生效（AppCompat 一般会自动重建，这里兜底）
        if (Prefs.themeMode(this) != appliedTheme) {
            appliedTheme = Prefs.themeMode(this);
            recreate();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("text", text));
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
    }
}
