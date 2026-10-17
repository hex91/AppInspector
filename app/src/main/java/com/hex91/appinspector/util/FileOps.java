package com.hex91.appinspector.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 文件操作：复制 / 移动 / 删除 / 新建目录 / 新建文件 / 重命名。
 * 有 root 走 su -c 命令（可操作 /data、/system 等任意路径）；
 * 无 root 走 File API（/sdcard 写入需「所有文件访问」权限，失败时 Result 会给出提示）。
 */
public class FileOps {

    public static class Result {
        public boolean ok;
        public String message;
        public String data; // 操作后的新路径

        public Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }

        public Result(boolean ok, String message, String data) {
            this.ok = ok;
            this.message = message;
            this.data = data;
        }
    }

    private static boolean root = false;

    /** 每次操作前同步当前 root 状态 */
    public static void setRoot(boolean r) {
        root = r;
    }

    // ---------- 复制 / 移动 ----------

    /** 把 src（文件或目录）复制到 dstDir 下，同名自动加序号 */
    public static Result copyTo(String src, String dstDir) {
        String dst = unique(dstDir, new File(src).getName());
        if (root) {
            RootShell.Result r = RootShell.run("cp -r \"" + src + "\" \"" + dst + "\"", 180);
            return r.exitCode == 0
                    ? new Result(true, "已复制到 " + dst, dst)
                    : new Result(false, r.message());
        }
        try {
            File s = new File(src);
            File d = new File(dst);
            if (s.isDirectory()) copyDir(s, d);
            else copyFile(s, d);
            return new Result(true, "已复制到 " + dst, dst);
        } catch (Exception e) {
            return new Result(false, err(e));
        }
    }

    /** 把 src 移动到 dstDir 下，同名自动加序号 */
    public static Result moveTo(String src, String dstDir) {
        String dst = unique(dstDir, new File(src).getName());
        if (root) {
            RootShell.Result r = RootShell.run("mv \"" + src + "\" \"" + dst + "\"", 180);
            return r.exitCode == 0
                    ? new Result(true, "已移动到 " + dst, dst)
                    : new Result(false, r.message());
        }
        try {
            File s = new File(src);
            File d = new File(dst);
            if (!s.renameTo(d)) {
                // 跨存储卷时 renameTo 失败，退化为复制 + 删除
                if (s.isDirectory()) copyDir(s, d);
                else copyFile(s, d);
                deleteRec(s);
            }
            return new Result(true, "已移动到 " + dst, dst);
        } catch (Exception e) {
            return new Result(false, err(e));
        }
    }

    // ---------- 删除 ----------

    public static Result delete(String path) {
        if (root) {
            RootShell.Result r = RootShell.run("rm -rf \"" + path + "\"", 180);
            return r.exitCode == 0
                    ? new Result(true, "已删除 " + path, path)
                    : new Result(false, r.message());
        }
        try {
            deleteRec(new File(path));
            return new Result(true, "已删除 " + path, path);
        } catch (Exception e) {
            return new Result(false, err(e));
        }
    }

    // ---------- 新建 ----------

    public static Result mkdir(String parent, String name) {
        String path = join(parent, name);
        if (root) {
            RootShell.Result r = RootShell.run("mkdir -p \"" + path + "\"", 30);
            return r.exitCode == 0
                    ? new Result(true, "已创建目录 " + path, path)
                    : new Result(false, r.message());
        }
        try {
            File d = new File(path);
            if (d.exists()) return new Result(false, "已存在同名目录");
            if (!d.mkdirs()) return new Result(false, "创建失败（无 Root 时对 /sdcard 写入需要「所有文件访问」权限）");
            return new Result(true, "已创建目录 " + path, path);
        } catch (Exception e) {
            return new Result(false, err(e));
        }
    }

    public static Result createFile(String parent, String name) {
        String path = join(parent, name);
        if (root) {
            RootShell.Result r = RootShell.run("touch \"" + path + "\"", 30);
            return r.exitCode == 0
                    ? new Result(true, "已创建文件 " + path, path)
                    : new Result(false, r.message());
        }
        try {
            File f = new File(path);
            if (f.exists()) return new Result(false, "已存在同名文件");
            if (!f.createNewFile()) return new Result(false, "创建失败（无 Root 时对 /sdcard 写入需要「所有文件访问」权限）");
            return new Result(true, "已创建文件 " + path, path);
        } catch (Exception e) {
            return new Result(false, err(e));
        }
    }

    // ---------- 重命名 ----------

    public static Result rename(String path, String newName) {
        if (newName == null || newName.trim().isEmpty()) return new Result(false, "名称不能为空");
        newName = newName.trim();
        File f = new File(path);
        String target = join(f.getParent(), newName);
        if (new File(target).exists()) return new Result(false, "已存在同名文件/目录");
        if (root) {
            RootShell.Result r = RootShell.run("mv \"" + path + "\" \"" + target + "\"", 60);
            return r.exitCode == 0
                    ? new Result(true, "已重命名为 " + target, target)
                    : new Result(false, r.message());
        }
        try {
            if (!f.renameTo(new File(target))) return new Result(false, "重命名失败（可能需要 Root 或文件访问权限）");
            return new Result(true, "已重命名为 " + target, target);
        } catch (Exception e) {
            return new Result(false, err(e));
        }
    }

    // ---------- 工具 ----------

    private static String join(String parent, String name) {
        if (parent == null || parent.isEmpty()) parent = "/";
        return parent.endsWith("/") ? parent + name : parent + "/" + name;
    }

    /** 目标存在时自动加 (1) (2) 序号 */
    static String unique(String dir, String name) {
        if (!new File(dir, name).exists()) return join(dir, name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 999; i++) {
            String cand = join(dir, base + "(" + i + ")" + ext);
            if (!new File(cand).exists()) return cand;
        }
        return join(dir, base + "_" + System.currentTimeMillis() + ext);
    }

    static void copyFile(File s, File d) throws Exception {
        try (InputStream in = new FileInputStream(s);
             OutputStream out = new FileOutputStream(d)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        }
    }

    static void copyDir(File s, File d) throws Exception {
        if (!d.mkdirs() && !d.isDirectory()) throw new Exception("无法创建目录 " + d);
        File[] cs = s.listFiles();
        if (cs == null) return;
        for (File c : cs) {
            if (c.isDirectory()) copyDir(c, new File(d, c.getName()));
            else copyFile(c, new File(d, c.getName()));
        }
    }

    static void deleteRec(File f) {
        File[] cs = f.listFiles();
        if (cs != null) for (File c : cs) deleteRec(c);
        f.delete();
    }

    private static String err(Exception e) {
        String m = e.getMessage() == null ? e.toString() : e.getMessage();
        return m + "\n\n提示：无 Root 时对 /sdcard 的写入需要「所有文件访问」权限，可在右上角菜单授权，或改用 Root。";
    }
}
