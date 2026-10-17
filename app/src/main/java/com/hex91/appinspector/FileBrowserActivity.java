package com.hex91.appinspector;

import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
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
import com.hex91.appinspector.util.ApkInfoParser;
import com.hex91.appinspector.util.ApkInstaller;
import com.hex91.appinspector.util.FileClipboard;
import com.hex91.appinspector.util.FileLister;
import com.hex91.appinspector.util.FileOps;
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
 * 文件管理器：浏览目录（Root 下可看 /data、/system），
 * 安装 APK / APKS / APKM / XAPK，
 * 复制 / 剪切 / 粘贴 / 删除 / 新建 / 重命名 / 查看 APK 基础信息。
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
            } else if (id == R.id.action_new_dir) {
                showCreateDialog(true);
                return true;
            } else if (id == R.id.action_new_file) {
                showCreateDialog(false);
                return true;
            } else if (id == R.id.action_paste) {
                pasteHere();
                return true;
            } else if (id == R.id.action_grant) {
                grantAccess();
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
            runOnUiThread(this::updateSubtitle);
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
        final boolean apk = it.name.toLowerCase(Locale.ROOT).endsWith(".apk");
        final boolean split = SplitArchive.isSplitArchive(it.name);

        List<String> ops = new ArrayList<>();
        ops.add(it.dir ? "打开目录"
                : (apk ? "安装" : (split ? "解析安装" : "尝试打开")));
        if (apk) ops.add("APK 信息");
        ops.add("复制");
        ops.add("剪切");
        ops.add("重命名");
        ops.add("删除");
        ops.add("文件信息");
        ops.add("复制路径");

        new AlertDialog.Builder(this)
                .setTitle(it.name)
                .setItems(ops.toArray(new String[0]), (d, w) -> {
                    int idx = w;
                    // 第一项固定是打开/安装
                    if (idx-- == 0) {
                        onClick(it);
                        return;
                    }
                    if (apk) {
                        if (idx-- == 0) {
                            showApkInfo(it);
                            return;
                        }
                    }
                    if (idx-- == 0) {
                        doCopy(it);
                        return;
                    }
                    if (idx-- == 0) {
                        doCut(it);
                        return;
                    }
                    if (idx-- == 0) {
                        showRenameDialog(it);
                        return;
                    }
                    if (idx-- == 0) {
                        confirmDelete(it);
                        return;
                    }
                    if (idx-- == 0) {
                        showInfo(it);
                        return;
                    }
                    copy(it.path);
                })
                .show();
    }

    // ---------- 复制 / 剪切 / 粘贴 ----------

    private void doCopy(FileLister.Item it) {
        List<String> one = new ArrayList<>();
        one.add(it.path);
        FileClipboard.set(one, false);
        updateSubtitle();
        Toast.makeText(this, "已复制，到目标目录点菜单「粘贴到当前目录」", Toast.LENGTH_SHORT).show();
    }

    private void doCut(FileLister.Item it) {
        List<String> one = new ArrayList<>();
        one.add(it.path);
        FileClipboard.set(one, true);
        updateSubtitle();
        Toast.makeText(this, "已剪切，到目标目录点菜单「粘贴到当前目录」", Toast.LENGTH_SHORT).show();
    }

    private void pasteHere() {
        if (!FileClipboard.has()) {
            Toast.makeText(this, "剪贴板为空：请先长按文件选择「复制」或「剪切」",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final List<String> srcs = FileClipboard.paths();
        final boolean cut = FileClipboard.isCut();
        StringBuilder sb = new StringBuilder();
        sb.append(cut ? "移动 " : "复制 ").append(srcs.size()).append(" 项到\n")
          .append(currentPath).append("\n\n");
        for (String s : srcs) sb.append("· ").append(new File(s).getName()).append("\n");
        new AlertDialog.Builder(this)
                .setTitle(cut ? "确认移动" : "确认复制")
                .setMessage(sb.toString())
                .setNegativeButton("取消", null)
                .setPositiveButton(cut ? "移动" : "复制", (d, w) -> {
                    FileOps.setRoot(rootOk);
                    runOp("处理中…", () -> {
                        StringBuilder result = new StringBuilder();
                        boolean allOk = true;
                        for (String s : srcs) {
                            FileOps.Result r = cut ? FileOps.moveTo(s, currentPath)
                                    : FileOps.copyTo(s, currentPath);
                            result.append(r.ok ? "✓ " : "✗ ").append(r.message).append("\n");
                            if (!r.ok) allOk = false;
                        }
                        if (cut) FileClipboard.clear();
                        final boolean ok = allOk;
                        final String msg = result.toString();
                        runOnUiThread(() -> {
                            updateSubtitle();
                            new AlertDialog.Builder(this)
                                    .setTitle(ok ? "粘贴完成" : "部分失败")
                                    .setMessage(msg)
                                    .setPositiveButton("关闭", (dd, ww) -> load(currentPath))
                                    .show();
                        });
                    });
                })
                .show();
    }

    // ---------- 删除 / 新建 / 重命名 ----------

    private void confirmDelete(FileLister.Item it) {
        new AlertDialog.Builder(this)
                .setTitle("确认删除")
                .setMessage("将删除\n" + it.path
                        + (it.dir ? "\n（目录及内部全部内容）" : "")
                        + "\n\n此操作不可恢复，继续？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    FileOps.setRoot(rootOk);
                    runOp("删除中…", () -> {
                        final FileOps.Result r = FileOps.delete(it.path);
                        runOnUiThread(() -> {
                            new AlertDialog.Builder(this)
                                    .setTitle(r.ok ? "已删除" : "删除失败")
                                    .setMessage(r.message)
                                    .setPositiveButton("关闭", (dd, ww) -> load(currentPath))
                                    .show();
                        });
                    });
                })
                .show();
    }

    private void showCreateDialog(final boolean dir) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setHint(dir ? "目录名称" : "文件名称");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(32, 16, 32, 0);
        box.addView(et);
        new AlertDialog.Builder(this)
                .setTitle(dir ? "新建目录" : "新建文件")
                .setMessage("位置：" + currentPath)
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("创建", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    FileOps.setRoot(rootOk);
                    runOp("创建中…", () -> {
                        final FileOps.Result r = dir
                                ? FileOps.mkdir(currentPath, name)
                                : FileOps.createFile(currentPath, name);
                        runOnUiThread(() -> {
                            if (r.ok) {
                                Toast.makeText(this, r.message, Toast.LENGTH_SHORT).show();
                                load(currentPath);
                            } else {
                                new AlertDialog.Builder(this)
                                        .setTitle("创建失败")
                                        .setMessage(r.message)
                                        .setPositiveButton("关闭", null)
                                        .show();
                            }
                        });
                    });
                })
                .show();
    }

    private void showRenameDialog(final FileLister.Item it) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(it.name);
        et.setSelection(et.getText().length());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(32, 16, 32, 0);
        box.addView(et);
        new AlertDialog.Builder(this)
                .setTitle("重命名")
                .setMessage(it.path)
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    FileOps.setRoot(rootOk);
                    runOp("重命名中…", () -> {
                        final FileOps.Result r = FileOps.rename(it.path, name);
                        runOnUiThread(() -> {
                            if (r.ok) {
                                Toast.makeText(this, r.message, Toast.LENGTH_SHORT).show();
                                load(currentPath);
                            } else {
                                new AlertDialog.Builder(this)
                                        .setTitle("重命名失败")
                                        .setMessage(r.message)
                                        .setPositiveButton("关闭", null)
                                        .show();
                            }
                        });
                    });
                })
                .show();
    }

    // ---------- APK 信息 ----------

    private void showApkInfo(final FileLister.Item it) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("解析 " + it.name + " …");
        pd.setCancelable(false);
        pd.show();
        executor.execute(() -> {
            final ApkInfoParser.Info in = ApkInfoParser.parse(this, it.path, rootOk);
            runOnUiThread(() -> {
                pd.dismiss();
                StringBuilder sb = new StringBuilder();
                if (!in.parsed) {
                    sb.append("无法解析该 APK（文件可能损坏，或路径无读取权限）");
                } else {
                    sb.append("应用名：").append(in.label.isEmpty() ? "(未知)" : in.label).append("\n");
                    sb.append("包名：").append(in.packageName).append("\n");
                    sb.append("版本：").append(in.versionName)
                      .append(" (").append(in.versionCode).append(")\n");
                    sb.append("targetSdk：").append(in.targetSdk)
                      .append("   minSdk：").append(in.minSdk).append("\n");
                    sb.append("组件：Activity ").append(in.nActivity)
                      .append(" / Service ").append(in.nService)
                      .append(" / Receiver ").append(in.nReceiver)
                      .append(" / Provider ").append(in.nProvider).append("\n");
                    if (!in.signers.isEmpty()) {
                        sb.append("\n签名 SHA-256：\n").append(in.signers.get(0)).append("\n");
                    }
                    if (!in.permissions.isEmpty()) {
                        sb.append("\n申请权限（共 ").append(in.permissions.size()).append(" 条）：\n");
                        int limit = Math.min(in.permissions.size(), 15);
                        for (int i = 0; i < limit; i++) {
                            sb.append("· ").append(in.permissions.get(i)
                                    .replace("android.permission.", "")).append("\n");
                        }
                        if (in.permissions.size() > limit) {
                            sb.append("… 等其余 ").append(in.permissions.size() - limit).append(" 条");
                        }
                    }
                }
                new AlertDialog.Builder(this)
                        .setTitle("APK 信息")
                        .setMessage(sb.toString())
                        .setNegativeButton("关闭", null)
                        .setNeutralButton("复制", (d, w) -> copy(sb.toString()))
                        .setPositiveButton("安装", (d, w) -> {
                            List<String> one = new ArrayList<>();
                            one.add(it.path);
                            ApkInstaller.install(this, one, it.name);
                        })
                        .show();
            });
        });
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

    // ---------- 文件信息 ----------

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

    // ---------- 路径跳转 / 预设 / 授权 ----------

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

    private void grantAccess() {
        if (Environment.isExternalStorageManager()) {
            Toast.makeText(this, "已拥有「所有文件访问」权限", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("文件访问授权")
                .setMessage("无 Root 时，对 /sdcard 的写入（复制/新建/删除）需要「所有文件访问」权限。\n"
                        + "有 Root 则无需此权限。是否前往系统设置授权？")
                .setNegativeButton("取消", null)
                .setPositiveButton("去授权", (d, w) -> {
                    try {
                        startActivity(new Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    }
                })
                .show();
    }

    // ---------- 异步操作封装 ----------

    private void runOp(String message, Runnable body) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(message);
        pd.setCancelable(false);
        pd.show();
        executor.execute(() -> {
            try {
                body.run();
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle("操作异常")
                            .setMessage(e.getMessage() == null ? e.toString() : e.getMessage())
                            .setPositiveButton("关闭", null)
                            .show();
                });
                return;
            }
            runOnUiThread(pd::dismiss);
        });
    }

    private void updateSubtitle() {
        StringBuilder sb = new StringBuilder();
        sb.append(rootOk ? "Root 已授权" : "无 Root");
        if (FileClipboard.has()) {
            sb.append("  ·  剪贴板 ").append(FileClipboard.size())
              .append(" 项").append(FileClipboard.isCut() ? "（剪切）" : "（复制）");
        }
        if (!rootOk && !Environment.isExternalStorageManager()) {
            sb.append("  ·  写入需授权");
        }
        toolbar.setSubtitle(sb.toString());
        toolbar.setSubtitleTextColor(getResources().getColor(
                rootOk ? R.color.green : R.color.red, getTheme()));
    }

    // ---------- 主题 / 生命周期 ----------

    @Override
    protected void onResume() {
        super.onResume();
        // 主题设置页修改后返回时立即生效（AppCompat 一般会自动重建，这里兜底）
        if (Prefs.themeMode(this) != appliedTheme) {
            appliedTheme = Prefs.themeMode(this);
            recreate();
            return;
        }
        updateSubtitle();
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
