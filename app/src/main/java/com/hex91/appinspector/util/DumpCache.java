package com.hex91.appinspector.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** 缓存某个包名的 intent-filter 解析结果（dumpsys 较慢，只跑一次） */
public class DumpCache {

    private static final Map<String, Map<String, String>> CACHE =
            Collections.synchronizedMap(new HashMap<>());
    private static final Map<String, Object> LOCKS =
            Collections.synchronizedMap(new HashMap<>());

    /** 返回 组件全类名 -> intent-filter 摘要；无 root 时返回空 map */
    public static Map<String, String> get(String pkg) {
        Map<String, String> cached = CACHE.get(pkg);
        if (cached != null) return cached;

        synchronized (lockOf(pkg)) {
            cached = CACHE.get(pkg);
            if (cached != null) return cached;
            Map<String, String> parsed = new HashMap<>();
            try {
                if (RootShell.hasRoot()) {
                    RootShell.Result r = RootShell.run("dumpsys package " + pkg, 30);
                    if (r.ok() && !r.stdout.isEmpty()) {
                        parsed = DumpsysParser.parse(r.stdout, pkg);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            CACHE.put(pkg, parsed);
            return parsed;
        }
    }

    public static void clear() {
        CACHE.clear();
    }

    private static Object lockOf(String pkg) {
        Object o = LOCKS.get(pkg);
        if (o == null) {
            o = new Object();
            LOCKS.put(pkg, o);
        }
        return o;
    }
}
