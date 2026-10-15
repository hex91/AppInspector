package com.hex91.appinspector.util;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.hex91.appinspector.model.AppInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 提取已安装应用的 APK 到 Download 目录。
 * 优先级：root cp（最快） → MediaStore（API 29+） → 公共目录 File（API 28）
 */
public class ApkExporter {

    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    public static void export(final Activity act, final AppInfo info, final Runnable onDone) {
        POOL.execute(() -> {
            String src = info.sourceDir;
            final Result r = new Result();
            r.src = src;
            r.splitCount = info.splitCount;
            try {
                String fileName = buildName(info);
                if (RootShell.hasRoot()) {
                    doRootCopy(r, src, fileName);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    doMediaStore(act, r, src, fileName);
                } else {
                    doLegacyFile(act, r, src, fileName);
                }
            } catch (Exception e) {
                r.error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            act.runOnUiThread(() -> showResult(act, info, r, onDone));
        });
    }

    // ---------- 三种落盘方式 ----------

    private static void doRootCopy(Result r, String src, String fileName) throws Exception {
        String out = "/sdcard/Download/" + fileName;
        // 双引号防空格，末尾 chmod 保证文件管理器可读
        String cmd = "cp \"" + src + "\" \"" + out + "\" && chmod 644 \"" + out + "\""
                + " && am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE"
                + " -d file://\"" + out + "\" >/dev/null 2>&1";
        RootShell.Result rr = RootShell.run(cmd, 60);
        if (rr.exitCode != 0 && !rr.stdout.contains("result=0")) {
            throw new Exception("root 复制失败：" + rr.message());
        }
        r.path = out;
    }

    private static void doMediaStore(Context ctx, Result r, String src, String fileName) throws Exception {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        cv.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive");
        cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new Exception("无法创建 Download 文件（MediaStore）");
        OutputStream os = ctx.getContentResolver().openOutputStream(uri);
        copy(new FileInputStream(src), os);
        r.path = "/storage/emulated/0/Download/" + fileName;
    }

    private static void doLegacyFile(Context ctx, Result r, String src, String fileName) throws Exception {
        File dir = new File(Environment.getExternalStorageDirectory(), "Download");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("无法创建 Download 目录：" + dir.getAbsolutePath());
        }
        File out = new File(dir, fileName);
        copy(new FileInputStream(src), new FileOutputStream(out));
        // 通知媒体库扫描，否则文件管理器里看不到
        ctx.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(out)));
        r.path = out.getAbsolutePath();
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        if (out == null) throw new Exception("无法打开输出流");
        byte[] buf = new byte[1 << 16];
        int n;
        try {
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            try { in.close(); } catch (Exception ignored) {}
            try { out.close(); } catch (Exception ignored) {}
        }
    }

    // ---------- 命名与结果展示 ----------

    private static String buildName(AppInfo info) {
        String label = info.label == null ? info.packageName : info.label;
        label = label.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        String ver = TextUtils.isEmpty(info.versionName) ? "" : "_" + info.versionName;
        return label + ver + "_" + info.packageName + ".apk";
    }

    private static void showResult(Activity act, AppInfo info, Result r, Runnable onDone) {
        if (r.error != null) {
            new AlertDialog.Builder(act)
                    .setTitle("提取失败")
                    .setMessage("源文件：" + r.src + "\n错误：" + r.error)
                    .setPositiveButton("关闭", null)
                    .show();
            return;
        }
        StringBuilder msg = new StringBuilder();
        msg.append("已保存到：\n").append(r.path);
        if (info.splitCount > 0) {
            msg.append("\n\n注意：该应用含 ").append(info.splitCount)
               .append(" 个 split APK（资源/架构分包），此处仅导出 base.apk。"
                       + "单独安装 base.apk 可能缺资源，需要完整安装请改用备份或 pm 相关命令。");
        }
        new AlertDialog.Builder(act)
                .setTitle("提取完成")
                .setMessage(msg.toString())
                .setPositiveButton("关闭", (d, w) -> {
                    if (onDone != null) onDone.run();
                })
                .setNeutralButton("复制路径", (d, w) -> copy(act, r.path))
                .show();
        if (onDone != null) onDone.run();
        Toast.makeText(act, "已保存到 Download", Toast.LENGTH_SHORT).show();
    }

    private static void copy(Context ctx, String text) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("path", text));
        Toast.makeText(ctx, "路径已复制", Toast.LENGTH_SHORT).show();
    }

    private static class Result {
        String src;
        String path;
        String error;
        int splitCount;
    }
}
