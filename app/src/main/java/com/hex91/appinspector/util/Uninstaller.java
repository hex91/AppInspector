package com.hex91.appinspector.util;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.hex91.appinspector.model.AppInfo;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 卸载 / 移除应用。
 * 两条路径：
 * 1. 常规：Intent.ACTION_DELETE 交给系统卸载器（有确认弹窗，用户可取消）
 * 2. Root：su 执行 pm uninstall / pm disable-user（静默、可处理系统应用）
 */
public class Uninstaller {

    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    private static final int M_NORMAL = 0;      // 系统卸载器
    private static final int M_ROOT = 1;        // pm uninstall
    private static final int M_ROOT_KEEP = 2;   // pm uninstall -k 保留数据
    private static final int M_DISABLE = 3;     // pm disable-user 禁用（可逆）
    private static final int M_RESTORE = 4;     // pm install-existing 恢复系统应用

    public static void show(final Activity act, final AppInfo info, final Runnable onDone) {
        ScrollView sv = new ScrollView(act);
        LinearLayout ll = new LinearLayout(act);
        ll.setOrientation(LinearLayout.VERTICAL);
        int p = dp(act, 20);
        ll.setPadding(p, dp(act, 8), p, 0);
        sv.addView(ll);

        TextView tvInfo = new TextView(act);
        tvInfo.setText(info.label + "\n" + info.packageName
                + (info.isSystem ? "\n[系统应用]" : "\n[三方应用]"));
        tvInfo.setTextSize(13f);
        tvInfo.setTextColor(Color.parseColor("#333333"));
        tvInfo.setPadding(0, 0, 0, dp(act, 12));
        ll.addView(tvInfo);

        final RadioGroup rg = new RadioGroup(act);
        addMode(act, rg, M_NORMAL, "常规卸载（系统弹窗确认）",
                "交给系统卸载器，用户可取消；系统应用只会卸载更新");
        addMode(act, rg, M_ROOT, "Root：pm uninstall（静默）",
                "无确认直接删除，可移除系统应用");
        addMode(act, rg, M_ROOT_KEEP, "Root：pm uninstall -k（保留数据）",
                "删除应用但保留 /data 数据，重装后数据还在");
        addMode(act, rg, M_DISABLE, "Root：pm disable-user（禁用，可逆）",
                "推荐用于系统应用：不删除文件，随时可恢复");
        addMode(act, rg, M_RESTORE, "Root：pm install-existing（恢复）",
                "恢复被卸载/禁用的系统应用");
        rg.check(rg.getChildAt(info.isSystem ? M_DISABLE : M_NORMAL).getId());
        ll.addView(rg);

        TextView tvHint = new TextView(act);
        tvHint.setTextSize(12f);
        tvHint.setTextColor(Color.parseColor("#8A6D00"));
        tvHint.setPadding(0, dp(act, 12), 0, dp(act, 8));
        tvHint.setText(act.getString(com.hex91.appinspector.R.string.uninstall_hint));
        ll.addView(tvHint);

        new AlertDialog.Builder(act)
                .setTitle("卸载 / 移除应用")
                .setView(sv)
                .setNegativeButton("取消", null)
                .setNeutralButton("复制命令", (d, w) ->
                        copy(act, buildCmd(info.packageName, modeOf(rg))))
                .setPositiveButton("执行", (d, w) -> {
                    int mode = modeOf(rg);
                    if (mode == M_NORMAL) {
                        uninstallNormal(act, info);
                    } else if (mode == M_RESTORE) {
                        run(act, info.packageName, buildCmd(info.packageName, mode), onDone, false);
                    } else {
                        confirm(act, info, mode, onDone);
                    }
                })
                .show();
    }

    private static void addMode(Activity act, RadioGroup rg, int id, String title, String desc) {
        RadioButton rb = new RadioButton(act);
        rb.setId(2000 + id);
        rb.setTag(id);
        rb.setText(title + "\n" + desc);
        rb.setTextSize(13f);
        rb.setPadding(0, dp(act, 6), 0, dp(act, 6));
        rb.setGravity(Gravity.TOP);
        rg.addView(rb);
    }

    private static int modeOf(RadioGroup rg) {
        View v = rg.findViewById(rg.getCheckedRadioButtonId());
        if (v != null && v.getTag() instanceof Integer) return (Integer) v.getTag();
        return M_NORMAL;
    }

    public static String buildCmd(String pkg, int mode) {
        switch (mode) {
            case M_ROOT:
                return "pm uninstall --user 0 " + pkg;
            case M_ROOT_KEEP:
                return "pm uninstall -k --user 0 " + pkg;
            case M_DISABLE:
                return "pm disable-user --user 0 " + pkg;
            case M_RESTORE:
                return "pm install-existing --user 0 " + pkg;
            default:
                return "# 常规方式：Intent.ACTION_DELETE (package:" + pkg + ")";
        }
    }

    /** 系统卸载器 */
    private static void uninstallNormal(Activity act, AppInfo info) {
        try {
            Intent it = new Intent(Intent.ACTION_DELETE);
            it.setData(Uri.parse("package:" + info.packageName));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(it);
            Toast.makeText(act, "已唤起系统卸载器", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            new AlertDialog.Builder(act)
                    .setTitle("无法唤起系统卸载器")
                    .setMessage(e.toString() + "\n\n可改用 Root 方式卸载。")
                    .setPositiveButton("关闭", null)
                    .show();
        }
    }

    /** 危险操作二次确认 */
    private static void confirm(final Activity act, final AppInfo info,
                                final int mode, final Runnable onDone) {
        String what = mode == M_DISABLE ? "禁用" : "卸载";
        String msg = "确定" + what + "「" + info.label + "」？\n\n"
                + "命令：" + buildCmd(info.packageName, mode);
        if (mode == M_ROOT && info.isSystem) {
            msg += "\n\n注意：这是系统应用，卸载后可用 pm install-existing 恢复，"
                    + "但部分 ROM 上可能导致功能异常。建议改用「禁用」。";
        }
        new AlertDialog.Builder(act)
                .setTitle("确认" + what)
                .setMessage(msg)
                .setPositiveButton("确定", (d, w) ->
                        run(act, info.packageName, buildCmd(info.packageName, mode), onDone, true))
                .setNegativeButton("取消", null)
                .show();
    }

    private static void run(final Activity act, final String pkg, final String cmd,
                            final Runnable onDone, final boolean forceStopFirst) {
        POOL.execute(() -> {
            RootShell.Result r;
            if (forceStopFirst) {
                r = RootShell.run("am force-stop " + pkg + " ; " + cmd, 30);
            } else {
                r = RootShell.run(cmd, 30);
            }
            final RootShell.Result res = r;
            act.runOnUiThread(() -> {
                boolean ok = res.ok() || (res.stdout + res.stderr).contains("Success");
                String msg = "$ " + cmd + "\n\n" + res.message();
                if (!ok) {
                    msg += "\n\n常见原因：\n"
                            + "· Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]：被设备管理器锁定，先去设置里解除\n"
                            + "· Failure [DELETE_FAILED_INTERNAL_ERROR]：应用正在运行，已尝试先强制停止\n"
                            + "· 系统应用请先禁用，或确认 ROM 允许卸载";
                }
                new AlertDialog.Builder(act)
                        .setTitle(ok ? "执行成功" : "执行返回错误")
                        .setMessage(msg)
                        .setPositiveButton("关闭", (d, w) -> {
                            if (onDone != null) onDone.run();
                        })
                        .setNeutralButton("复制命令", (d, w) -> copy(act, cmd))
                        .show();
                Toast.makeText(act, ok ? "操作完成" : "执行失败", Toast.LENGTH_SHORT).show();
                if (ok && onDone != null) onDone.run();
            });
        });
    }

    /** 手动输入包名恢复系统应用（救砖用） */
    public static void showRestore(final Activity act) {
        final android.widget.EditText et = new android.widget.EditText(act);
        et.setHint("例如 com.android.calendar");
        et.setSingleLine(true);
        LinearLayout ll = new LinearLayout(act);
        ll.setPadding(dp(act, 20), dp(act, 10), dp(act, 20), 0);
        ll.addView(et);

        new AlertDialog.Builder(act)
                .setTitle("恢复系统应用")
                .setMessage("用于恢复被卸载或禁用的系统应用（pm install-existing）。")
                .setView(ll)
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复", (d, w) -> {
                    String pkg = et.getText().toString().trim();
                    if (pkg.isEmpty()) return;
                    String cmd = buildCmd(pkg, M_RESTORE);
                    POOL.execute(() -> {
                        final RootShell.Result r = RootShell.run(cmd, 30);
                        act.runOnUiThread(() -> new AlertDialog.Builder(act)
                                .setTitle(r.ok() ? "执行成功" : "执行返回错误")
                                .setMessage("$ " + cmd + "\n\n" + r.message())
                                .setPositiveButton("关闭", null)
                                .show());
                    });
                })
                .show();
    }

    private static void copy(Context ctx, String text) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("cmd", text));
        Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show();
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
