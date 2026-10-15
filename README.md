# AppInspector — Root 应用组件研判工具

面向移动安全/逆向分析的 Android 工具：列出已安装应用、查看四大组件、用 root 权限直接拉起任意 Activity（包括 `exported=false` 的），用于快速研判 App 功能、跳过注册/引导流程直达内层页面。

## 功能

### 1. 应用列表
- 显示 **图标 / 应用名 / 包名 / 版本名+版本号 / targetSdk**
- 支持按应用名或包名搜索
- 支持「全部 / 三方 / 系统」过滤
- 长按可：查看组件、复制包名、跳转系统应用信息页、强制停止、清除数据（`pm clear`）、查看详情

### 2. 四大组件
进入任一应用后，按 Tab 分开展示：
- **Activity**
- **Service**
- **BroadcastReceiver**
- **ContentProvider**

每个组件显示：
- 完整类名 + 简写
- `exported`（已导出/未导出，红色即未导出）
- 所需权限 `permission`、所在进程 `processName`
- Activity 的 `launchMode`、Provider 的 `authority`
- 声明的 **intent-filter**（通过 root 解析 `dumpsys package` 得到，官方 API 拿不到）
- 长按可看完整详情、复制类名
- 右上角菜单可导出全部组件为 txt 并分享

### 3. 启动/调用组件（核心）
点击任意组件弹出启动对话框，可按组件类型选择不同方式：

| 组件 | 可执行操作 |
|---|---|
| Activity | 常规 `startActivity`；`am start -n`；**先 force-stop 再启动**（重置状态）；清空任务栈启动 |
| Service | `am startservice`；`am start-foreground-service`；常规 `startService` |
| Receiver | `am broadcast -n`；常规 `sendBroadcast` |
| Provider | `content query --uri content://authority` |

可附加参数：
- **Action**（如 `android.intent.action.VIEW`）
- **Data URI**
- **Extra**：每行一条 `key=value`，支持类型前缀
  - `key=abc` → `--es key "abc"`（字符串）
  - `i:key=123` → `--ei`
  - `l:key=123` → `--el`
  - `b:key=true` → `--ez`

所有 root 命令都会回显完整 shell 输出，可一键复制后在电脑上用 `adb shell` 复现。

### 4. 卸载 / 移除应用
应用列表**长按** →「卸载 / 移除应用」，提供五种方式：

| 方式 | 命令 / 行为 | 适用场景 |
|---|---|---|
| 常规卸载 | `Intent.ACTION_DELETE`，唤起系统卸载器 | 三方应用，有确认弹窗可取消 |
| Root：pm uninstall | `pm uninstall --user 0 <pkg>` | 静默删除，可移除系统应用 |
| Root：pm uninstall -k | `pm uninstall -k --user 0 <pkg>` | 删应用但保留数据 |
| Root：pm disable-user | `pm disable-user --user 0 <pkg>` | **系统应用首选**，可逆不删文件 |
| Root：pm install-existing | `pm install-existing --user 0 <pkg>` | 恢复被卸载/禁用的系统应用 |

- 系统应用默认勾选「禁用」，避免误删导致 ROM 功能异常
- 卸载前会自动先 `am force-stop`，提高成功率
- Root 方式执行前有一次二次确认，命令可一键复制
- 菜单 →「恢复系统应用」：手动输入包名执行 `pm install-existing`，用于误删系统组件后救回

### 5. 提取安装包
应用列表**长按** →「提取安装包 (APK) 到 Download」。

- 落盘路径按优先级：**root `cp` 到 `/sdcard/Download/`** → **MediaStore**（API 29+）→ 公共目录 File（API 28，需存储权限）
- 文件名：`应用名_版本号_包名.apk`
- 复制完自动触发媒体扫描，文件管理器里立即可见
- 检测到 split APK（App Bundle 分包）时提示仅导出 base.apk
- 完成后弹窗显示完整路径，可一键复制

### 6. 附带实用功能
- 菜单「当前界面」：读取当前栈顶 Activity（先试 `cmd activity get-top-activity`，失败再解析 `dumpsys activity`）。切到别的 App 再回来点，就能看到对方当前停在哪个页面，配合组件列表定位非常快。
- 菜单「Root 状态」：检测 `su` 是否可用。
- 工具栏实时显示 Root 授权状态。

## 编译

环境：**JDK 17+**、**Gradle 9.5**、**AGP 9.3.2**、**Android SDK Platform 36 + Build-Tools 36.1.0**

```bash
# 用 Android Studio 直接打开 AppInspector 目录即可
# 或命令行：
gradle assembleDebug
```

产出：`app/build/outputs/apk/debug/app-debug.apk`（已附一份 `AppInspector-v1.0.apk` 在根目录）

> 已配置 `minSdk 28`（Android 9+）、`targetSdk 36`。
> `local.properties` 里写死了本机 SDK 路径 `D:\ProgramData\Android\sdk`，换机器需修改。
> gradle wrapper 与依赖仓库已换成国内镜像（腾讯云 / 阿里云），避免 Gradle 官方源连不上。

安装：
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 已配置 `minSdk 28`（Android 9+）、`targetSdk 36`。
> `local.properties` 里写死了本机 SDK 路径，换机器需修改或用 Android Studio 自动生成。

## 使用前提

1. 手机已 root（Magisk / KernelSU 等）。
2. 首次进入应用，**Magisk 会弹出 root 授权请求，必须允许**。
3. Android 11+ 已在 Manifest 声明 `QUERY_ALL_PACKAGES`，可列出全部应用（部分国产 ROM 首次安装会提示权限，允许即可）。

## 跳过注册/登录的常用套路

1. 打开目标 App → 在 AppInspector 里找到它 → 看 Activity 列表里带 `android.intent.action.MAIN` 的入口，以及 `Main` / `Home` / `Tab` / `Container` 之类的壳容器。
2. 用「Root：am start -n」直接拉起主界面 Activity。若原始入口是注册/登录页，这一步就能绕过去（前提是该页面本身不依赖登录态数据）。
3. 打开后如果是空白/闪退，说明该页面依赖上层传参：回到组件详情看 intent-filter 需要的 action，或在 Extra 里补参数（例如 `i:id=123`、`token=xxx`）。
4. 需要重置状态时用「先强制停止再启动」；需要清掉本地登录态时用长按菜单的「清除数据」（`pm clear`，会清空该 App 全部数据，谨慎）。
5. 想知道现在停在哪个界面：菜单 →「当前界面」。

## 常见问题

**Q：启动返回 `Error type 3`？**
组件名不存在或类名写错。注意 `am start -n` 需要的是 `包名/完整类名`；内部类是 `com.x.A$B` 这种形式。

**Q：返回 `Permission Denial: not exported`？**
说明走的不是 root 通道。确认工具栏显示「Root 已授权」，并选用带「Root：」前缀的方式。

**Q：启动后页面空白？**
多为缺参数。看该组件的 intent-filter 与 jadx 反编译代码里 `getIntent().getStringExtra(...)` 的取值 key，在 Extra 里补齐。

**Q：intent-filter 一直是空的？**
该项依赖 root 解析 `dumpsys package <包名>`。无 root 时四大组件基本信息照常显示，只是没有 intent-filter。

**Q：MIUI / ColorOS 上启动失败？**
部分 ROM 需要关闭「MIUI 优化」；有些 ROM 对后台启动 Activity 有限制，优先用「先强制停止再启动」。

## 目录结构

```
app/src/main/java/com/hex91/appinspector/
├── MainActivity.java              应用列表页
├── ComponentsActivity.java        四大组件容器页（Tab + ViewPager2）
├── fragment/CompPageFragment.java 单类组件列表
├── adapter/                       列表适配器
├── model/                         AppInfo、CompInfo
└── util/
    ├── RootShell.java             su 命令执行（带超时、双流读取）
    ├── PkgScanner.java            PackageManager 扫描应用与组件
    ├── DumpsysParser.java         解析 dumpsys 得到 intent-filter
    ├── DumpCache.java             dumpsys 结果缓存
    ├── IconCache.java             图标 LRU 缓存
    ├── Launcher.java              组件启动器（命令构造 + 执行 + 回显）
    └── Uninstaller.java           卸载器（常规 / pm uninstall / disable / 恢复）
    └── ApkExporter.java            APK 提取到 Download（root cp / MediaStore / File）
```

## 版本

- v1.0：应用列表 + 四大组件 + root 启动器
- v1.1：新增卸载 / 移除应用（常规 + Root 静默 + 禁用 + 恢复），回到列表自动刷新
- v1.2：新增长按提取 APK 到 Download 目录
