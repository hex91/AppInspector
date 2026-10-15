package com.hex91.appinspector.model;

/** 四大组件中的一项 */
public class CompInfo {

    public static final int TYPE_ACTIVITY = 0;
    public static final int TYPE_SERVICE = 1;
    public static final int TYPE_RECEIVER = 2;
    public static final int TYPE_PROVIDER = 3;

    public int type;
    public String pkg;              // 所属包名
    public String name;             // 完整类名 com.xxx.MainActivity
    public String shortName;        // 简写 .MainActivity 或 MainActivity
    public boolean exported;
    public boolean enabled;
    public String permission;       // 启动所需权限
    public String processName;      // 所在进程
    public String authority;        // Provider 才有
    public String launchMode;       // Activity 才有
    public String filterSummary;    // intent-filter 摘要（来自 dumpsys）
    public String rawDump;          // dumpsys 中的原始片段（详情用）

    /** 详情文本 */
    public String detail() {
        StringBuilder sb = new StringBuilder();
        sb.append("类名：").append(name).append("\n");
        sb.append("类型：").append(typeName()).append("\n");
        sb.append("exported：").append(exported).append("\n");
        sb.append("enabled：").append(enabled).append("\n");
        if (permission != null && !permission.isEmpty()) {
            sb.append("权限：").append(permission).append("\n");
        }
        if (processName != null && !processName.isEmpty()) {
            sb.append("进程：").append(processName).append("\n");
        }
        if (launchMode != null && !launchMode.isEmpty()) {
            sb.append("启动模式：").append(launchMode).append("\n");
        }
        if (authority != null && !authority.isEmpty()) {
            sb.append("authority：").append(authority).append("\n");
        }
        if (filterSummary != null && !filterSummary.isEmpty()) {
            sb.append("\nintent-filter：\n").append(filterSummary);
        }
        if (rawDump != null && !rawDump.isEmpty()) {
            sb.append("\n\ndumpsys 片段：\n").append(rawDump);
        }
        return sb.toString();
    }

    public String typeName() {
        switch (type) {
            case TYPE_SERVICE:  return "Service";
            case TYPE_RECEIVER: return "BroadcastReceiver";
            case TYPE_PROVIDER: return "ContentProvider";
            default:            return "Activity";
        }
    }
}
