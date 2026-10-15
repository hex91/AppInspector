package com.hex91.appinspector.model;

/** 一个已安装应用的摘要信息 */
public class AppInfo {
    public String label;            // 应用名
    public String packageName;      // 包名
    public String versionName;      // 版本名
    public long versionCode;        // 版本号
    public boolean isSystem;        // 是否系统应用
    public boolean enabled;         // 是否被禁用
    public long firstInstallTime;
    public long lastUpdateTime;
    public int targetSdk;
    public int minSdk;
    public int uid;
    public String sourceDir;        // apk 路径
    public int splitCount;          // split APK 数量（0 表示单包）
    public String installer;        // 安装来源

    /** 用于搜索匹配的字符串 */
    public String lowerKey() {
        return (label + " " + packageName).toLowerCase();
    }
}
