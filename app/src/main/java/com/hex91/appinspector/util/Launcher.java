package com.hex91.appinspector.util;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.hex91.appinspector.R;
import com.hex91.appinspector.model.CompInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 组件启动器：支持常规 API 启动与 root(su) 的 am/命令启动。
 * root 方式可以拉起 exported=false 的组件，这是跳过注册/引导页直达内层页面的关键。
 */
public class Launcher {

    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    // ===== 启动模式 =====
    // Activity
    private static final int A_NORMAL = 0;   // 常规 startActivity
    private static final int A_ROOT = 1;     // am start
    private static final int A_ROOT_STOP = 2;// force-stop + am start
    private static final int A_ROOT_CLEAR = 3; // clear-task + new-task
    // Service
    private static final int S_ROOT = 0;
    private static final int S_ROOT_FG = 1;
    private static final int S_NORMAL = 2;
    // Receiver
    private static final int R_ROOT = 0;
    private static final int R_NORMAL = 1;
    // Provider
    private static final int P_ROOT_QUERY = 0;

    public static void show(final Activity act, final CompInfo c) {
        View v = act.getLayoutInflater().inflate(R.layout.dialog_launch, null);
        final TextView tvTarget = v.findViewById(R.id.tvTarget);
        final RadioGroup rg = v.findViewById(R.id.rgMode);
        final EditText etAction = v.findViewById(R.id.etAction);
        final EditText etData = v.findViewById(R.id.etData);
        final EditText etExtras = v.findViewById(R.id.etExtras);
        final TextView tvHint = v.findViewById(R.id.tvHint);

        tvTarget.setText(c.typeName() + "：" + c.name
                + (c.exported ? "" : "（未导出，普通方式无法启动）"));

        addModes(act, rg, c);

        String hint;
        switch (c.type) {
            case CompInfo.TYPE_ACTIVITY:
                hint = "提示：root 方式通过 am start 拉起，可启动未导出的 Activity；"
                        + "若打开的是空白页，通常是该页面依赖上层传入的参数，请在 Extra 中补齐。";
                break;
            case CompInfo.TYPE_SERVICE:
                hint = "提示：Android 8+ 起后台启动服务受限，优先用 root 的 start-foreground-service。";
                break;
            case CompInfo.TYPE_RECEIVER:
                hint = "提示：显式广播（指定组件名）不受 Android 8 隐式广播限制，root 方式更稳。";
                break;
            default:
                hint = "提示：Provider 通过 content query 访问 content://" + c.authority + "。";
                break;
        }
        tvHint.setText(hint);

        // Activity 页面默认填充 Main 的 action 提示
        rg.check(rg.getChildAt(defaultMode(c)).getId());

        new AlertDialog.Builder(act)
                .setTitle("启动组件")
                .setView(v)
                .setNegativeButton("取消", null)
                .setNeutralButton("复制命令", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        copy(act, buildCmd(c, getMode(rg), text(etAction), text(etData), text(etExtras)));
                    }
                })
                .setPositiveButton("执行", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        final int mode = getMode(rg);
                        final String action = text(etAction);
                        final String data = text(etData);
                        final String extras = text(etExtras);
                        execute(act, c, mode, action, data, extras);
                    }
                })
                .show();
    }

    private static int defaultMode(CompInfo c) {
        switch (c.type) {
            case CompInfo.TYPE_ACTIVITY:
                return RootShell.hasRoot() ? A_ROOT : A_NORMAL;
            default:
                return 0;
        }
    }

    private static void addModes(Activity act, RadioGroup rg, CompInfo c) {
        String[] titles;
        switch (c.type) {
            case CompInfo.TYPE_ACTIVITY:
                titles = new String[]{
                        "常规 startActivity（需 exported=true）",
                        "Root：am start -n 组件名",
                        "Root：先强制停止再启动（重置状态）",
                        "Root：am start 清空任务栈启动"
                };
                break;
            case CompInfo.TYPE_SERVICE:
                titles = new String[]{
                        "Root：am startservice",
                        "Root：am start-foreground-service",
                        "常规 startService（需 exported=true）"
                };
                break;
            case CompInfo.TYPE_RECEIVER:
                titles = new String[]{
                        "Root：am broadcast -n 组件名",
                        "常规 sendBroadcast（需 exported=true）"
                };
                break;
            default:
                titles = new String[]{"Root：content query --uri content://" + c.authority};
                break;
        }
        for (int i = 0; i < titles.length; i++) {
            RadioButton rb = new RadioButton(act);
            rb.setId(1000 + i);
            rb.setText(titles[i]);
            rb.setTextSize(13f);
            rb.setTag(i);
            rg.addView(rb);
        }
    }

    private static int getMode(RadioGroup rg) {
        int id = rg.getCheckedRadioButtonId();
        View v = rg.findViewById(id);
        if (v != null && v.getTag() instanceof Integer) return (Integer) v.getTag();
        return 0;
    }

    private static String text(EditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    // ================= 命令构造 =================

    public static String buildCmd(CompInfo c, int mode, String action, String data, String extras) {
        String comp = c.pkg + "/" + c.name;
        StringBuilder sb = new StringBuilder();
        List<Extra> exs = parseExtras(extras);

        switch (c.type) {
            case CompInfo.TYPE_ACTIVITY:
                if (mode == A_NORMAL) {
                    sb.append("# 常规方式（App 内 Intent）\n")
                      .append("Intent.setClassName(\"").append(c.pkg).append("\", \"")
                      .append(c.name).append("\")");
                    return sb.toString();
                }
                if (mode == A_ROOT_STOP) {
                    sb.append("am force-stop ").append(c.pkg).append(" && ");
                }
                sb.append("am start -n ").append(comp);
                if (mode == A_ROOT_CLEAR) {
                    sb.append(" --activity-clear-task --activity-new-task");
                }
                if (!TextUtils.isEmpty(action)) sb.append(" -a ").append(action);
                if (!TextUtils.isEmpty(data)) sb.append(" -d \"").append(esc(data)).append("\"");
                sb.append(extrasArgs(exs));
                sb.append(" --user 0");
                return sb.toString();

            case CompInfo.TYPE_SERVICE:
                if (mode == S_NORMAL) {
                    sb.append("# 常规方式\nIntent.setClassName(\"").append(c.pkg)
                      .append("\", \"").append(c.name).append("\")");
                    return sb.toString();
                }
                sb.append(mode == S_ROOT_FG ? "am start-foreground-service -n " : "am startservice -n ")
                  .append(comp);
                if (!TextUtils.isEmpty(action)) sb.append(" -a ").append(action);
                sb.append(extrasArgs(exs));
                sb.append(" --user 0");
                return sb.toString();

            case CompInfo.TYPE_RECEIVER:
                if (mode == R_NORMAL) {
                    sb.append("# 常规方式\nsendBroadcast(Intent().setClassName(\"")
                      .append(c.pkg).append("\", \"").append(c.name).append("\"))");
                    return sb.toString();
                }
                sb.append("am broadcast -n ").append(comp);
                if (!TextUtils.isEmpty(action)) sb.append(" -a ").append(action);
                sb.append(extrasArgs(exs));
                sb.append(" --user 0");
                return sb.toString();

            default:
                sb.append("content query --uri \"content://").append(c.authority).append("\"");
                if (!TextUtils.isEmpty(data)) sb.append(" --where \"").append(esc(data)).append("\"");
                return sb.toString();
        }
    }

    private static String extrasArgs(List<Extra> exs) {
        StringBuilder sb = new StringBuilder();
        for (Extra e : exs) {
            switch (e.type) {
                case 'i': sb.append(" --ei ").append(e.key).append(" ").append(e.value); break;
                case 'l': sb.append(" --el ").append(e.key).append(" ").append(e.value); break;
                case 'b': sb.append(" --ez ").append(e.key).append(" ").append(e.value); break;
                default:  sb.append(" --es ").append(e.key).append(" \"").append(esc(e.value)).append("\"");
            }
        }
        return sb.toString();
    }

    private static class Extra {
        char type = 's';
        String key;
        String value;
    }

    /** 每行一条：key=value / i:key=1 / l:key=1 / b:key=true */
    private static List<Extra> parseExtras(String raw) {
        List<Extra> list = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) return list;
        for (String line : raw.split("\n")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            Extra e = new Extra();
            if (s.length() > 2 && s.charAt(1) == ':'
                    && (s.charAt(0) == 'i' || s.charAt(0) == 'l' || s.charAt(0) == 'b')) {
                e.type = s.charAt(0);
                s = s.substring(2);
            }
            int eq = s.indexOf('=');
            if (eq <= 0) continue;
            e.key = s.substring(0, eq).trim();
            e.value = s.substring(eq + 1).trim();
            if (e.key.isEmpty()) continue;
            list.add(e);
        }
        return list;
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("$", "\\$").replace("`", "\\`");
    }

    // ================= 执行 =================

    private static void execute(final Activity act, final CompInfo c, final int mode,
                                final String action, final String data, final String extras) {
        final String cmd = buildCmd(c, mode, action, data, extras);
        final boolean needRootMode = (c.type == CompInfo.TYPE_ACTIVITY && mode != A_NORMAL)
                || (c.type == CompInfo.TYPE_SERVICE && mode != S_NORMAL)
                || (c.type == CompInfo.TYPE_RECEIVER && mode != R_NORMAL)
                || c.type == CompInfo.TYPE_PROVIDER;

        if (!needRootMode) {
            // 常规 API 启动
            try {
                Intent it = new Intent();
                it.setComponent(new ComponentName(c.pkg, c.name));
                if (!TextUtils.isEmpty(action)) it.setAction(action);
                if (!TextUtils.isEmpty(data)) it.setData(Uri.parse(data));
                for (Extra e : parseExtras(extras)) {
                    switch (e.type) {
                        case 'i': it.putExtra(e.key, Integer.parseInt(e.value.replaceAll("[^0-9-]", ""))); break;
                        case 'l': it.putExtra(e.key, Long.parseLong(e.value.replaceAll("[^0-9-]", ""))); break;
                        case 'b': it.putExtra(e.key, Boolean.parseBoolean(e.value)); break;
                        default:  it.putExtra(e.key, e.value);
                    }
                }
                if (c.type == CompInfo.TYPE_SERVICE) {
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        act.startForegroundService(it);
                    } else {
                        act.startService(it);
                    }
                    toast(act, "已请求启动 Service");
                } else {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    act.startActivity(it);
                    toast(act, "已请求启动 Activity");
                }
            } catch (SecurityException se) {
                showResult(act, "启动失败（权限不足）",
                        "该组件未导出或声明了权限，请改用 Root 方式。\n\n" + se.getMessage(), cmd);
            } catch (Exception e) {
                showResult(act, "启动失败", e.toString(), cmd);
            }
            return;
        }

        // root / shell 方式
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                final RootShell.Result r = RootShell.run(cmd, 25);
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        String title = r.ok() ? "执行完成" : "执行返回错误";
                        String msg = r.timeout ? "命令超时（25s）\n" + r.message() : r.message();
                        if (!r.ok()) {
                            msg += "\n\n常见原因：\n"
                                    + "· Error type 3：组件不存在或包名/类名写错\n"
                                    + "· Permission Denial：组件未导出且 su 未授权本应用\n"
                                    + "· 该 ROM 限制了后台启动 Activity，试试先强制停止再启动";
                        }
                        showResult(act, title, msg, cmd);
                    }
                });
            }
        });
    }

    private static void showResult(final Activity act, String title, String msg, final String cmd) {
        final String content = "命令：\n" + cmd + "\n\n" + "输出：\n" + msg;
        new AlertDialog.Builder(act)
                .setTitle(title)
                .setMessage(content)
                .setPositiveButton("知道了", null)
                .setNeutralButton("复制命令", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        copy(act, cmd);
                    }
                })
                .setNegativeButton("复制全部", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        copy(act, content);
                    }
                })
                .show();
    }

    private static void copy(Context ctx, String text) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("cmd", text));
        Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show();
    }

    private static void toast(Context ctx, String s) {
        Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show();
    }
}
