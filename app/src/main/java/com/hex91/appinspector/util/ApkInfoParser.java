package com.hex91.appinspector.util;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * APK 基础信息解析（未安装的 apk 文件也能解析）。
 * 通过 PackageManager.getPackageArchiveInfo 读取：
 * 应用名 / 包名 / 版本 / targetSdk / minSdk / 四大组件数量 / 申请权限 / 签名 SHA-256。
 * root 下对 /data 等受保护路径，先拷到 cache 再解析。
 */
public class ApkInfoParser {

    public static class Info {
        public boolean parsed = false;   // 是否成功解析出包信息
        public String path;
        public String label = "";
        public String packageName = "";
        public String versionName = "";
        public long versionCode;
        public int targetSdk;
        public int minSdk;
        public int nActivity, nService, nReceiver, nProvider;
        public List<String> permissions = new ArrayList<>();
        public List<String> signers = new ArrayList<>(); // 签名证书 SHA-256
    }

    public static Info parse(Context ctx, String path, boolean root) {
        Info in = new Info();
        in.path = path;

        String p = path;
        // root 且普通进程读不了该路径时，先拷贝到 cache 再解析
        if (root && ctx.getPackageManager().getPackageArchiveInfo(p, 0) == null) {
            try {
                File tmp = new File(ctx.getCacheDir(),
                        "apkinspect_" + new File(path).getName());
                try (InputStream is = new FileInputStream(path);
                     OutputStream os = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                }
                p = tmp.getAbsolutePath();
            } catch (Exception ignored) {
            }
        }

        int flags = PackageManager.GET_PERMISSIONS
                | PackageManager.GET_ACTIVITIES
                | PackageManager.GET_SERVICES
                | PackageManager.GET_RECEIVERS
                | PackageManager.GET_PROVIDERS
                | PackageManager.GET_SIGNING_CERTIFICATES;

        try {
            PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(p, flags);
            if (pi == null) return in;

            in.parsed = true;
            in.packageName = pi.packageName == null ? "" : pi.packageName;
            in.versionName = pi.versionName == null ? "" : pi.versionName;
            in.versionCode = pi.getLongVersionCode();

            ApplicationInfo ai = pi.applicationInfo;
            if (ai != null) {
                in.targetSdk = ai.targetSdkVersion;
                in.minSdk = ai.minSdkVersion;
                CharSequence l = ai.loadLabel(ctx.getPackageManager());
                in.label = l == null ? "" : l.toString();
            }

            in.nActivity = pi.activities == null ? 0 : pi.activities.length;
            in.nService = pi.services == null ? 0 : pi.services.length;
            in.nReceiver = pi.receivers == null ? 0 : pi.receivers.length;
            in.nProvider = pi.providers == null ? 0 : pi.providers.length;

            if (pi.requestedPermissions != null) {
                in.permissions.addAll(Arrays.asList(pi.requestedPermissions));
            }

            if (pi.signingInfo != null) {
                Signature[] sigs = pi.signingInfo.getApkContentsSigners();
                if (sigs != null) {
                    for (Signature s : sigs) {
                        in.signers.add(sha256(s.toByteArray()));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return in;
    }

    /** 证书 SHA-256，按字节分组便于阅读 */
    static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) {
                if (i > 0 && i % 2 == 0) sb.append(' ');
                sb.append(String.format("%02X", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "(计算失败)";
        }
    }
}
