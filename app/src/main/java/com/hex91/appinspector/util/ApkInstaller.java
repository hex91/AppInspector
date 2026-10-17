package com.hex91.appinspector.util;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * APK 安装器。
 * - 单 apk + 无 root：ACTION_VIEW 走系统安装器
 * - 多 apk（split / APKS / APKM / XAPK 解析结果）：
 *   root  → pm install-multiple
 *   无 root → PackageInstaller Session 多 apk 一次提交
 */
public class ApkInstaller {

    private static final String ACTION_RESULT =
            "com.hex91.appinspector.INSTALL_RESULT";

    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    /** 安装入口：paths 按顺序传入，1 个走单包，多个走 session/install-multiple */
    public static void install(final Activity act, final List<String> paths, final String title) {
        if (paths == null || paths.isEmpty()) return;
        if (paths.size() == 1 && !RootShell.hasRoot()) {
            installSingle(act, paths.get(0));
            return;
        }
        showModeDialog(act, paths, title);
    }

    private static void installSingle(Activity act, String path) {
        try {
            File f = new File(path);
            if (!f.exists()) {
                Toast.makeText(act, "文件不存在：" + path, Toast.LENGTH_LONG).show();
                return;
            }
            Uri uri = FileProvider.getUriForFile(act,
                    act.getPackageName() + ".fileprovider", f);
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(uri, "application/vnd.android.package-archive");
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(it);
        } catch (Exception e) {
            new AlertDialog.Builder(act)
                    .setTitle("无法启动安装器")
                    .setMessage(e.toString() + "\n\n若是 /data 等受保护目录，请改用 Root 安装。")
                    .setPositiveButton("关闭", null)
                    .show();
        }
    }

    private static void showModeDialog(final Activity act, final List<String> paths, String title) {
        String msg = "共 " + paths.size() + " 个 APK 文件：\n";
        for (String p : paths) msg += "· " + new File(p).getName() + "\n";
        final boolean root = RootShell.hasRoot();
        msg += "\n" + (root ? "检测到 Root，可用 pm install-multiple 静默安装；"
                + "也可走系统安装器逐包确认。" : "未检测到 Root，将通过系统安装器一次性安装全部 APK。");

        new AlertDialog.Builder(act)
                .setTitle("安装 " + (title == null ? "" : title))
                .setMessage(msg)
                .setNegativeButton("取消", null)
                .setNeutralButton("复制命令", (d, w) ->
                        copy(act, buildCmd(paths)))
                .setPositiveButton(root ? "Root 安装" : "系统安装器", (d, w) -> {
                    if (root) {
                        runRoot(act, paths);
                    } else {
                        runSession(act, paths);
                    }
                })
                .show();
    }

    // ---------- root：pm install-multiple ----------

    public static String buildCmd(List<String> paths) {
        StringBuilder sb = new StringBuilder("pm install-multiple -r -t");
        for (String p : paths) sb.append(" \"").append(p).append("\"");
        return sb.toString();
    }

    private static void runRoot(final Activity act, final List<String> paths) {
        final String cmd = buildCmd(paths);
        POOL.execute(() -> {
            final RootShell.Result r = RootShell.run(cmd, 120);
            act.runOnUiThread(() -> new AlertDialog.Builder(act)
                    .setTitle(r.ok() ? "安装成功" : "安装返回错误")
                    .setMessage("$ " + cmd + "\n\n" + r.message())
                    .setPositiveButton("关闭", null)
                    .setNeutralButton("复制", (d, w) -> copy(act, cmd))
                    .show());
        });
    }

    // ---------- 无 root：PackageInstaller Session 多 apk ----------

    private static void runSession(final Activity act, final List<String> paths) {
        POOL.execute(() -> {
            try {
                Context ctx = act.getApplicationContext();
                PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                int sessionId = pi.createSession(params);
                PackageInstaller.Session session = pi.openSession(sessionId);
                for (String p : paths) {
                    File f = new File(p);
                    try (InputStream in = new FileInputStream(f);
                         OutputStream out = session.openWrite(f.getName(), 0, f.length())) {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        session.fsync(out);
                    }
                }

                IntentFilter filter = new IntentFilter(ACTION_RESULT);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ctx.registerReceiver(new ResultReceiver(act), filter,
                            Context.RECEIVER_EXPORTED);
                } else {
                    ctx.registerReceiver(new ResultReceiver(act), filter);
                }

                Intent intent = new Intent(ACTION_RESULT)
                        .setPackage(ctx.getPackageName());
                PendingIntent pi2 = PendingIntent.getBroadcast(
                        ctx, sessionId, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                session.commit(pi2.getIntentSender());
                session.close();
                act.runOnUiThread(() -> Toast.makeText(act,
                        "已提交系统安装器，请在弹窗中确认", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                act.runOnUiThread(() -> new AlertDialog.Builder(act)
                        .setTitle("提交安装失败")
                        .setMessage(e.toString())
                        .setPositiveButton("关闭", null)
                        .show());
            }
        });
    }

    private static class ResultReceiver extends BroadcastReceiver {
        private final Activity act;

        ResultReceiver(Activity act) {
            this.act = act;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            boolean ok = status == PackageInstaller.STATUS_SUCCESS;
            new AlertDialog.Builder(act)
                    .setTitle(ok ? "安装成功" : "安装失败")
                    .setMessage(ok ? "全部 APK 已安装完成" : "状态码 " + status + "\n" + msg)
                    .setPositiveButton("关闭", null)
                    .show();
            try {
                context.unregisterReceiver(this);
            } catch (Exception ignored) {
            }
        }
    }

    private static void copy(Context ctx, String text) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("cmd", text));
        Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show();
    }

    /** 供外部判断是否需要走多包流程 */
    public static boolean isApk(String name) {
        return name != null && name.toLowerCase().endsWith(".apk");
    }
}
