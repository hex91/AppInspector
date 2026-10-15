package com.hex91.appinspector.util;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.text.TextUtils;

import com.hex91.appinspector.model.AppInfo;
import com.hex91.appinspector.model.CompInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 扫描已安装应用与四大组件 */
public class PkgScanner {

    private static final int FLAGS = PackageManager.GET_ACTIVITIES
            | PackageManager.GET_SERVICES
            | PackageManager.GET_RECEIVERS
            | PackageManager.GET_PROVIDERS
            | PackageManager.GET_META_DATA
            | PackageManager.GET_DISABLED_COMPONENTS
            | PackageManager.GET_INTENT_FILTERS;

    private static final Map<String, List<CompInfo>[]> COMP_CACHE =
            Collections.synchronizedMap(new HashMap<>());

    /** 带缓存的组件扫描（四个 Fragment 共用同一次结果） */
    public static List<CompInfo>[] scanComponentsCached(Context ctx, String pkg) {
        List<CompInfo>[] cached = COMP_CACHE.get(pkg);
        if (cached != null) return cached;
        List<CompInfo>[] r = scanComponents(ctx, pkg);
        COMP_CACHE.put(pkg, r);
        return r;
    }

    public static void clearCache() {
        COMP_CACHE.clear();
    }

    /** 扫描全部应用 */
    public static List<AppInfo> scanApps(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<PackageInfo> pis = pm.getInstalledPackages(PackageManager.GET_META_DATA);
        List<AppInfo> list = new ArrayList<>();
        for (PackageInfo pi : pis) {
            ApplicationInfo ai = pi.applicationInfo;
            if (ai == null) continue;
            AppInfo a = new AppInfo();
            a.packageName = pi.packageName;
            String label = null;
            try {
                CharSequence cs = ai.loadLabel(pm);
                label = cs == null ? null : cs.toString();
            } catch (Exception ignored) {
            }
            a.label = TextUtils.isEmpty(label) ? pi.packageName : label;
            a.versionName = pi.versionName == null ? "" : pi.versionName;
            a.versionCode = pi.getLongVersionCode();
            a.isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            a.enabled = ai.enabled;
            a.firstInstallTime = pi.firstInstallTime;
            a.lastUpdateTime = pi.lastUpdateTime;
            a.targetSdk = ai.targetSdkVersion;
            a.minSdk = ai.minSdkVersion;
            a.uid = ai.uid;
            a.sourceDir = ai.sourceDir;
            a.splitCount = ai.splitSourceDirs == null ? 0 : ai.splitSourceDirs.length;
            list.add(a);
        }
        Collections.sort(list, new Comparator<AppInfo>() {
            @Override
            public int compare(AppInfo o1, AppInfo o2) {
                if (o1.isSystem != o2.isSystem) return o1.isSystem ? 1 : -1;
                return o1.label.compareToIgnoreCase(o2.label);
            }
        });
        return list;
    }

    /** 获取某应用的全部组件 */
    public static List<CompInfo>[] scanComponents(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        @SuppressWarnings("unchecked")
        List<CompInfo>[] result = new List[4];
        for (int i = 0; i < 4; i++) result[i] = new ArrayList<>();
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, FLAGS);
            if (pi.activities != null) {
                for (ActivityInfo ai : pi.activities) result[0].add(fromActivity(pkg, ai));
            }
            if (pi.services != null) {
                for (ServiceInfo si : pi.services) result[1].add(fromService(pkg, si));
            }
            if (pi.receivers != null) {
                for (ActivityInfo ri : pi.receivers) result[2].add(fromReceiver(pkg, ri));
            }
            if (pi.providers != null) {
                for (ProviderInfo vi : pi.providers) result[3].add(fromProvider(pkg, vi));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        for (int i = 0; i < 4; i++) {
            Collections.sort(result[i], new Comparator<CompInfo>() {
                @Override
                public int compare(CompInfo o1, CompInfo o2) {
                    return o1.name.compareToIgnoreCase(o2.name);
                }
            });
        }
        return result;
    }

    private static String shortOf(String pkg, String name) {
        if (name.startsWith(pkg)) return name.substring(pkg.length());
        int i = name.lastIndexOf('.');
        if (i >= 0 && i < name.length() - 1) {
            String s = name.substring(i + 1);
            // 内部类形式的简写前面补上外部类名更可读
            return s;
        }
        return name;
    }

    private static String launchModeStr(int mode) {
        switch (mode) {
            case ActivityInfo.LAUNCH_SINGLE_TOP: return "singleTop";
            case ActivityInfo.LAUNCH_SINGLE_TASK: return "singleTask";
            case ActivityInfo.LAUNCH_SINGLE_INSTANCE: return "singleInstance";
            default: return "standard";
        }
    }

    private static CompInfo fromActivity(String pkg, ActivityInfo ai) {
        CompInfo c = new CompInfo();
        c.type = CompInfo.TYPE_ACTIVITY;
        c.pkg = pkg;
        c.name = ai.name;
        c.shortName = shortOf(pkg, ai.name);
        c.exported = ai.exported;
        c.enabled = ai.enabled;
        c.permission = ai.permission;
        c.processName = ai.processName;
        c.launchMode = launchModeStr(ai.launchMode);
        return c;
    }

    private static CompInfo fromService(String pkg, ServiceInfo si) {
        CompInfo c = new CompInfo();
        c.type = CompInfo.TYPE_SERVICE;
        c.pkg = pkg;
        c.name = si.name;
        c.shortName = shortOf(pkg, si.name);
        c.exported = si.exported;
        c.enabled = si.enabled;
        c.permission = si.permission;
        c.processName = si.processName;
        return c;
    }

    private static CompInfo fromReceiver(String pkg, ActivityInfo ri) {
        CompInfo c = new CompInfo();
        c.type = CompInfo.TYPE_RECEIVER;
        c.pkg = pkg;
        c.name = ri.name;
        c.shortName = shortOf(pkg, ri.name);
        c.exported = ri.exported;
        c.enabled = ri.enabled;
        c.permission = ri.permission;
        c.processName = ri.processName;
        return c;
    }

    private static CompInfo fromProvider(String pkg, ProviderInfo vi) {
        CompInfo c = new CompInfo();
        c.type = CompInfo.TYPE_PROVIDER;
        c.pkg = pkg;
        c.name = vi.name;
        c.shortName = shortOf(pkg, vi.name);
        c.exported = vi.exported;
        c.enabled = vi.enabled;
        c.permission = vi.readPermission != null ? vi.readPermission : vi.writePermission;
        c.processName = vi.processName;
        c.authority = vi.authority;
        return c;
    }
}
