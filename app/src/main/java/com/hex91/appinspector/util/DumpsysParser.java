package com.hex91.appinspector.util;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 `dumpsys package <pkg>` 输出，提取每个组件声明的 intent-filter。
 * PackageManager 官方 API 拿不到 intent-filter，只能用 dumpsys（需要 root/shell 权限）。
 */
public class DumpsysParser {

    // 形如：  a1b2c3d com.foo/.ui.Main filter e4f5a6b
    private static final Pattern COMP_LINE =
            Pattern.compile("^[\\s]*[0-9a-fA-F]{6,}\\s+(\\S+\\/\\S+)\\s+filter\\s+");

    // 形如：  a1b2c3d com.foo/.ui.Main (offset=...) 兜底
    private static final Pattern COMP_LINE2 =
            Pattern.compile("^[\\s]*[0-9a-fA-F]{6,}\\s+(\\S+\\/\\S+)\\b");

    public static Map<String, String> parse(String dump, String pkg) {
        Map<String, StringBuilder> tmp = new HashMap<>();
        if (dump == null || dump.isEmpty()) return new HashMap<>();

        String current = null;
        StringBuilder curSb = null;

        for (String rawLine : dump.split("\n")) {
            String line = rawLine.trim();

            Matcher m = COMP_LINE.matcher(rawLine);
            boolean matched = m.find();
            if (!matched) {
                Matcher m2 = COMP_LINE2.matcher(rawLine);
                if (m2.find()) {
                    matched = true;
                    m = m2;
                }
            }
            if (matched) {
                String comp = normalize(m.group(1), pkg);
                if (comp == null) {
                    current = null;
                    curSb = null;
                    continue;
                }
                current = comp;
                curSb = tmp.get(comp);
                if (curSb == null) {
                    curSb = new StringBuilder();
                    tmp.put(comp, curSb);
                }
                continue;
            }

            if (current == null) continue;

            String kv = extract(line);
            if (kv != null) {
                if (curSb.length() > 0) curSb.append('\n');
                curSb.append(kv);
            } else if (line.endsWith("}") || line.startsWith("filter")) {
                // 段结束
                current = null;
                curSb = null;
            }
        }

        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, StringBuilder> e : tmp.entrySet()) {
            String v = e.getValue().toString().trim();
            if (!v.isEmpty()) out.put(e.getKey(), v);
        }
        return out;
    }

    /** 把 com.foo/.Main 归一化为全类名 com.foo.Main */
    private static String normalize(String comp, String pkg) {
        int slash = comp.indexOf('/');
        if (slash <= 0) return null;
        String p = comp.substring(0, slash);
        String cls = comp.substring(slash + 1);
        if (cls.startsWith(".")) {
            cls = p + cls;
        } else if (!cls.contains(".")) {
            cls = p + "." + cls;
        }
        if (pkg != null && !pkg.equals(p)) return null; // 只保留本应用的组件
        return cls;
    }

    /** 提取 Action:/Category:/Scheme:/Authority: 等行 */
    private static String extract(String line) {
        String[] keys = {"Action:", "Category:", "Scheme:", "Authority:", "Path:", "Type:"};
        for (String k : keys) {
            if (line.startsWith(k)) {
                String v = line.substring(k.length()).trim();
                v = trimQuote(v);
                if (v.isEmpty()) return null;
                return k.replace(":", "").toLowerCase() + ": " + v;
            }
        }
        return null;
    }

    private static String trimQuote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        // 形如 "android.intent.action.MAIN" 12345678
        int sp = s.indexOf(' ');
        if (sp > 0 && s.startsWith("\"")) {
            String first = s.substring(0, sp);
            if (first.length() >= 2 && first.endsWith("\"")) {
                return first.substring(1, first.length() - 1);
            }
        }
        return s;
    }
}
