package com.hex91.appinspector.util;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Root shell 执行工具。
 * 所有需要提权的操作（启动未导出 Activity、dumpsys、pm 等）都走这里。
 */
public class RootShell {

    private static final String TAG = "RootShell";
    private static Boolean sRoot = null;

    public static class Result {
        public int exitCode = -1;
        public String stdout = "";
        public String stderr = "";
        public String cmd = "";
        public boolean timeout = false;

        public boolean ok() {
            return exitCode == 0;
        }

        public String message() {
            StringBuilder sb = new StringBuilder();
            if (!stdout.isEmpty()) sb.append(stdout);
            if (!stderr.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(stderr);
            }
            String s = sb.toString().trim();
            return s.isEmpty() ? "(无输出)" : s;
        }
    }

    /** 以 root 身份执行（su -c） */
    public static Result run(String cmd) {
        return exec(cmd, true, 20);
    }

    /** 以 root 身份执行，可指定超时 */
    public static Result run(String cmd, int timeoutSec) {
        return exec(cmd, true, timeoutSec);
    }

    /** 以普通 shell 身份执行 */
    public static Result sh(String cmd) {
        return exec(cmd, false, 20);
    }

    /** 检测 root 是否可用（结果缓存） */
    public static synchronized boolean hasRoot() {
        if (sRoot != null) return sRoot;
        Result r = exec("id", true, 8);
        boolean ok = r.exitCode == 0 && r.stdout.contains("uid=0");
        if (!ok) {
            // 部分 ROM 的 su 输出在 stderr
            ok = r.exitCode == 0 && r.stderr.contains("uid=0");
        }
        sRoot = ok;
        Log.i(TAG, "hasRoot=" + ok);
        return ok;
    }

    public static synchronized void resetRootCache() {
        sRoot = null;
    }

    private static Result exec(String cmd, boolean root, int timeoutSec) {
        Result res = new Result();
        res.cmd = cmd;
        Process process = null;
        try {
            String[] argv = root
                    ? new String[]{"su", "-c", cmd}
                    : new String[]{"sh", "-c", cmd};
            process = Runtime.getRuntime().exec(argv);

            StreamGobbler outGobbler = new StreamGobbler(process.getInputStream());
            StreamGobbler errGobbler = new StreamGobbler(process.getErrorStream());
            Thread t1 = new Thread(outGobbler);
            Thread t2 = new Thread(errGobbler);
            t1.start();
            t2.start();

            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                res.timeout = true;
                process.destroy();
            }
            t1.join(3000);
            t2.join(3000);

            res.stdout = outGobbler.get();
            res.stderr = errGobbler.get();
            res.exitCode = finished ? process.exitValue() : -1;
        } catch (Exception e) {
            Log.e(TAG, "exec error: " + cmd, e);
            res.stderr = "执行异常：" + e.getMessage();
            res.exitCode = -1;
        } finally {
            if (process != null) process.destroy();
        }
        return res;
    }

    private static class StreamGobbler implements Runnable {
        private final java.io.InputStream is;
        private final StringBuilder sb = new StringBuilder();

        StreamGobbler(java.io.InputStream is) {
            this.is = is;
        }

        @Override
        public void run() {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                    // 防止 dumpsys 输出过大撑爆内存
                    if (sb.length() > 2_000_000) break;
                }
            } catch (Exception ignored) {
            }
        }

        String get() {
            return sb.toString();
        }
    }
}
