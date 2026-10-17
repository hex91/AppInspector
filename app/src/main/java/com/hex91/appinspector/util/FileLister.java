package com.hex91.appinspector.util;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 目录列举。有 root 时走 su -c ls，可浏览 /data/data、/system/app 等受保护目录；
 * 无 root 走普通 File API。
 */
public class FileLister {

    public static class Item {
        public String name;
        public String path;
        public boolean dir;
        public long size;
        public long mtime;
        public String perms;
    }

    /** 列举目录内容，失败返回 null */
    public static List<Item> list(String path, boolean root) {
        if (path == null || path.isEmpty()) path = "/";
        File f = new File(path);
        List<Item> items;
        if (root) {
            items = listRoot(path);
        } else {
            if (!f.isDirectory()) return null;
            File[] files = f.listFiles();
            if (files == null) return null;
            items = new ArrayList<>();
            for (File c : files) {
                Item it = new Item();
                it.name = c.getName();
                it.path = c.getAbsolutePath();
                it.dir = c.isDirectory();
                it.size = it.dir ? 0 : c.length();
                it.mtime = c.lastModified();
                items.add(it);
            }
        }
        // 目录在前，其余按名称排序
        Collections.sort(items, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                if (a.dir != b.dir) return a.dir ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return items;
    }

    private static final Pattern LS_LINE = Pattern.compile(
            "^([bcdlsp-][rwxstST-]{9})\\s+\\d+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+(\\S+\\s+\\S+\\s+\\S+)\\s+(.*)$");

    private static List<Item> listRoot(String path) {
        // -la 解析权限/大小/日期/名称；名称含空格时用贪婪匹配
        RootShell.Result r = RootShell.run("ls -la \"" + path + "\"", 20);
        List<Item> items = new ArrayList<>();
        for (String line : r.stdout.split("\n")) {
            Matcher m = LS_LINE.matcher(line);
            if (!m.find()) continue;
            String perms = m.group(1);
            String name = m.group(4).trim();
            if (name.isEmpty() || name.equals(".") || name.equals("..")) continue;
            // 部分 ls 实现会给目录名补 /
            if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
            // 处理 "name -> target" 软链接，取链接名
            int arrow = name.indexOf(" -> ");
            if (arrow > 0) name = name.substring(0, arrow);
            Item it = new Item();
            it.name = name;
            it.path = path.equals("/") ? "/" + name : path + "/" + name;
            it.dir = perms.startsWith("d");
            it.perms = perms;
            try {
                it.size = Long.parseLong(m.group(2));
            } catch (Exception ignored) {
            }
            it.mtime = 0;
            items.add(it);
        }
        return items;
    }
}
