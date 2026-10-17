package com.hex91.appinspector.util;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * APKS / APKM / XAPK 解析与提取。
 * 这三种格式本质都是 zip，内部包含 base.apk + 若干 split_config.*.apk：
 * - XAPK / APKM：带 manifest.json（package_name / version_name / apks 列表）
 * - APKS（bundletool）：splits/*.apk + toc.pb（二进制清单，此处只取 apk 列表）
 */
public class SplitArchive {

    public static class Parsed {
        public String file;             // 源文件
        public String packageName;      // manifest 解析得到，可能为空
        public String versionName;
        public long versionCode;
        public List<String> apks = new ArrayList<>();  // 解压后的 apk 路径
        public List<String> names = new ArrayList<>(); // 对应文件名
        public String manifest;         // manifest.json 原文（APKS 为空）
    }

    public static boolean isSplitArchive(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.endsWith(".apks") || n.endsWith(".apkm") || n.endsWith(".xapk");
    }

    /** 解析并解压内部 apk 到 cacheDir/split_<文件名>/，失败抛异常 */
    public static Parsed parse(Context ctx, String path) throws Exception {
        File src = new File(path);
        if (!src.exists()) throw new Exception("文件不存在：" + path);

        Parsed out = new Parsed();
        out.file = path;

        File dir = new File(ctx.getCacheDir(),
                "split_" + src.getName().replaceAll("[^a-zA-Z0-9._-]", "_"));
        if (dir.exists()) deleteRec(dir);
        if (!dir.mkdirs()) throw new Exception("无法创建解压目录");

        ZipFile zip = new ZipFile(src);
        try {
            // 1) manifest.json（XAPK/APKM 有；APKS 是 toc.pb）
            ZipEntry me = zip.getEntry("manifest.json");
            if (me != null) {
                InputStream is = zip.getInputStream(me);
                StringBuilder sb = new StringBuilder();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
                is.close();
                out.manifest = sb.toString();
                try {
                    JSONObject jo = new JSONObject(out.manifest);
                    out.packageName = jo.optString("package_name", null);
                    out.versionName = jo.optString("version_name", null);
                    out.versionCode = jo.optLong("version_code", 0);
                } catch (Exception ignored) {
                }
            }

            // 2) 所有 .apk 条目（base 优先）
            List<ZipEntry> apks = new ArrayList<>();
            java.util.Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                if (e.getName().toLowerCase().endsWith(".apk")) apks.add(e);
            }
            // 按条目名排序，base.apk 通常字典序在前，显式保证
            java.util.Collections.sort(apks, (a, b) -> {
                String an = a.getName(), bn = b.getName();
                boolean ab = an.toLowerCase().contains("base");
                boolean bb = bn.toLowerCase().contains("base");
                if (ab != bb) return ab ? -1 : 1;
                return an.compareTo(bn);
            });

            for (ZipEntry e : apks) {
                String name = new File(e.getName()).getName();
                File target = new File(dir, name);
                try (InputStream is = zip.getInputStream(e);
                     FileOutputStream fos = new FileOutputStream(target)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                }
                out.apks.add(target.getAbsolutePath());
                out.names.add(name);
            }
        } finally {
            zip.close();
        }

        if (out.apks.isEmpty()) {
            throw new Exception("压缩包内未找到 .apk 文件，可能不是有效的 APKS/APKM/XAPK");
        }
        return out;
    }

    private static void deleteRec(File f) {
        File[] cs = f.listFiles();
        if (cs != null) for (File c : cs) deleteRec(c);
        f.delete();
    }
}
