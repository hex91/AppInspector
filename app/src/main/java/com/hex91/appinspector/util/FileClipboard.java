package com.hex91.appinspector.util;

import java.util.ArrayList;
import java.util.List;

/** 文件剪贴板：复制/剪切的源路径，浏览到目标目录后「粘贴」落位 */
public class FileClipboard {

    private static final List<String> PATHS = new ArrayList<>();
    private static boolean CUT = false;

    public static void set(List<String> paths, boolean cut) {
        synchronized (PATHS) {
            PATHS.clear();
            if (paths != null) PATHS.addAll(paths);
            CUT = cut;
        }
    }

    public static List<String> paths() {
        synchronized (PATHS) {
            return new ArrayList<>(PATHS);
        }
    }

    public static boolean isCut() {
        return CUT;
    }

    public static boolean has() {
        synchronized (PATHS) {
            return !PATHS.isEmpty();
        }
    }

    public static int size() {
        synchronized (PATHS) {
            return PATHS.size();
        }
    }

    public static void clear() {
        synchronized (PATHS) {
            PATHS.clear();
            CUT = false;
        }
    }
}
