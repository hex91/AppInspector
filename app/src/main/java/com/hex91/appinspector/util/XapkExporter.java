package com.hex91.appinspector.util;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.hex91.appinspector.model.AppInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 反向打包：把已安装应用的 base.apk + 各 split apk 打包成 .xapk。
 * 结构遵循 XAPK 惯例：manifest.json + icon.png + *.apk
 */
public class XapkExporter {

    private static final ExecutorServiceX POOL = new ExecutorServiceX();

    public static void export(final Activity act, final AppInfo info, final Runnable onDone) {
        POOL.execute(() -> {
            String result = null;
            String error = null;
            try {
                result = doExport(act, info);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String path = result;
            final String err = error;
            act.runOnUiThread(() -> {
                if (err != null) {
                    new AlertDialog.Builder(act)
                            .setTitle("导出失败")
                            .setMessage(err)
                            .setPositiveButton("关闭", null)
                            .show();
                    return;
                }
                new AlertDialog.Builder(act)
                        .setTitle("已导出 XAPK")
                        .setMessage("路径：" + path)
                        .setPositiveButton("关闭", (d, w) -> {
                            if (onDone != null) onDone.run();
                        })
                        .setNeutralButton("复制路径", (d, w) -> copy(act, path))
                        .show();
                Toast.makeText(act, "已保存到 Download", Toast.LENGTH_SHORT).show();
                if (onDone != null) onDone.run();
            });
        });
    }

    private static String doExport(Context ctx, AppInfo info) throws Exception {
        // 收集源 apk：base + splits
        List<String> srcs = new ArrayList<>();
        if (info.sourceDir != null) srcs.add(info.sourceDir);
        if (info.splitPaths != null) srcs.addAll(info.splitPaths);
        if (srcs.isEmpty()) throw new Exception("未找到 APK 源文件");

        File work = new File(ctx.getCacheDir(), "xapk_export");
        deleteRec(work);
        work.mkdirs();

        // 1) 拷贝 apk 到工作目录并重命名
        List<String> fileNames = new ArrayList<>();
        for (int i = 0; i < srcs.size(); i++) {
            String name = i == 0 ? "base.apk" : new File(srcs.get(i)).getName();
            File dst = new File(work, name);
            copy(new File(srcs.get(i)), dst);
            fileNames.add(name);
        }

        // 2) 图标（尽力而为）
        String iconName = null;
        try {
            Drawable d = ctx.getPackageManager().getApplicationIcon(info.packageName);
            Bitmap bmp = drawableToBitmap(d);
            if (bmp != null) {
                iconName = "icon.png";
                File icon = new File(work, iconName);
                try (FileOutputStream fos = new FileOutputStream(icon)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                }
            }
        } catch (Exception ignored) {
        }

        // 3) manifest.json
        JSONObject man = new JSONObject();
        man.put("package_name", info.packageName);
        man.put("name", info.label);
        man.put("version_name", info.versionName == null ? "" : info.versionName);
        man.put("version_code", info.versionCode);
        JSONArray arr = new JSONArray();
        for (int i = 0; i < fileNames.size(); i++) {
            JSONObject o = new JSONObject();
            o.put("id", i == 0 ? "base" : fileNames.get(i).replace(".apk", ""));
            o.put("file", fileNames.get(i));
            arr.put(o);
        }
        man.put("apks", arr);
        File manFile = new File(work, "manifest.json");
        try (FileOutputStream fos = new FileOutputStream(manFile)) {
            fos.write(man.toString(2).getBytes("UTF-8"));
        }

        // 4) 打成 zip
        String safe = info.label.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        String zipName = safe + "_" + info.versionName + "_" + info.packageName + ".xapk";
        File zip = new File(ctx.getCacheDir(), zipName);
        if (zip.exists()) zip.delete();
        List<File> entries = new ArrayList<>();
        entries.add(manFile);
        if (iconName != null) entries.add(new File(work, iconName));
        for (String n : fileNames) entries.add(new File(work, n));
        zipFiles(entries, work, zip);

        // 5) 落到 Download
        return saveToDownload(ctx, zip, zipName);
    }

    private static String saveToDownload(Context ctx, File zip, String name) throws Exception {
        if (RootShell.hasRoot()) {
            String out = "/sdcard/Download/" + name;
            RootShell.Result r = RootShell.run(
                    "cp \"" + zip.getAbsolutePath() + "\" \"" + out + "\" && chmod 644 \"" + out + "\"", 60);
            if (r.exitCode == 0) return out;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = ctx.getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("无法创建 Download 文件");
            try (InputStream in = new FileInputStream(zip);
                 OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
                copyStream(in, os);
            }
            return "/storage/emulated/0/Download/" + name;
        }
        File dir = new File(Environment.getExternalStorageDirectory(), "Download");
        File dst = new File(dir, name);
        copy(zip, dst);
        return dst.getAbsolutePath();
    }

    // ---------- 工具 ----------

    private static void copy(File src, File dst) throws Exception {
        try (InputStream in = new FileInputStream(src);
             OutputStream os = new FileOutputStream(dst)) {
            copyStream(in, os);
        }
    }

    private static void copyStream(InputStream in, OutputStream os) throws Exception {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        os.flush();
    }

    private static void zipFiles(List<File> files, File base, File out) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            for (File f : files) {
                String entry = f.getName();
                zos.putNextEntry(new ZipEntry(entry));
                try (InputStream in = new FileInputStream(f)) {
                    copyStream(in, zos);
                }
                zos.closeEntry();
            }
        }
    }

    private static Bitmap drawableToBitmap(Drawable d) {
        if (d instanceof BitmapDrawable) return ((BitmapDrawable) d).getBitmap();
        int w = Math.max(d.getIntrinsicWidth(), 48);
        int h = Math.max(d.getIntrinsicHeight(), 48);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return b;
    }

    private static void deleteRec(File f) {
        File[] cs = f.listFiles();
        if (cs != null) for (File c : cs) deleteRec(c);
        f.delete();
    }

    private static void copy(Context ctx, String text) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("path", text));
        Toast.makeText(ctx, "路径已复制", Toast.LENGTH_SHORT).show();
    }

    private static class ExecutorServiceX {
        void execute(Runnable r) {
            new Thread(r).start();
        }
    }
}
