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

### 7. 主题：日间 / 夜间 / 跟随系统
菜单 →「主题设置」，三选一，立即生效并记忆：

- **跟随系统**：随手机系统深色模式自动切换
- **日间模式**：强制浅色
- **夜间模式**：强制深色

配色通过 `values-night` + `drawable-night` 实现，所有页面（列表、组件、文件浏览）统一适配；已修复 Android 15/16 强制 edge-to-edge 导致的全屏页面与状态栏重叠问题。

### 8. 文件浏览器 + 安装 APK
菜单 →「文件浏览 / 安装 APK」：

- 浏览任意目录，**有 Root 时可进入 `/data/data`、`/data/app`、`/system/app` 等受保护目录**（走 `su -c ls -la`）
- 预设目录：`/sdcard`、`Download`、`Documents`、`/data/local/tmp`、`/data/app`、`/data/data`、`/system/app`、`/system/priv-app`、`/system/framework`
- 支持「跳转路径」手动输入任意路径、`..` 返回上级
- 点击 `.apk` → 安装；长按 → 复制路径 / 查看大小权限时间

安装方式自动选择：
| 场景 | 方式 |
|---|---|
| 单 apk + 无 Root | `ACTION_VIEW` + FileProvider 唤起系统安装器 |
| 多 apk + Root | `pm install-multiple -r -t` 静默安装 |
| 多 apk + 无 Root | `PackageInstaller.Session` 一次性提交，系统安装器装全部 split |

### 9. Split 安装包：APKS / APKM / XAPK
针对 App Bundle / 分包安装包的「合并安装 + 拆分导出」：

- **合并安装**：文件浏览器点击 `.apks` / `.apkm` / `.xapk`，自动解压出内部全部 `.apk`（base.apk 优先排序），解析 `manifest.json` 显示包名/版本后一键安装
- **拆分导出**：应用列表长按 →「导出为 XAPK（合并安装包）」，把已安装应用的 `base.apk + split_config.*.apk` 重新打包为 `.xapk`（含 `manifest.json` + `icon.png`）落到 Download，可分享给其它设备用本工具或 SAIO 类工具安装

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

> 注意：若手机上的旧版签名与当前构建不一致（例如旧包来自其它调试密钥），`adb install -r` 会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，需先 `adb uninstall com.hex91.appinspector` 再装；重装后 KernelSU 的 root 授权需要重新允许一次。

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
├── SettingsActivity.java          主题设置（跟随系统 / 日间 / 夜间）
├── FileBrowserActivity.java       文件浏览器 + APK 安装入口
├── fragment/CompPageFragment.java 单类组件列表
├── adapter/                       列表适配器（AppAdapter / CompAdapter / FileAdapter）
├── model/                         AppInfo、CompInfo
└── util/
    ├── RootShell.java             su 命令执行（带超时、双流读取）
    ├── PkgScanner.java            PackageManager 扫描应用与组件
    ├── DumpsysParser.java         解析 dumpsys 得到 intent-filter
    ├── DumpCache.java             dumpsys 结果缓存
    ├── IconCache.java             图标 LRU 缓存
    ├── Launcher.java              组件启动器（命令构造 + 执行 + 回显）
    ├── Uninstaller.java           卸载器（常规 / pm uninstall / disable / 恢复）
    ├── ApkExporter.java            APK 提取到 Download（root cp / MediaStore / File）
    ├── XapkExporter.java          已安装应用反向打包为 .xapk（base + split）
    ├── SplitArchive.java          APKS / APKM / XAPK 解析解压
    ├── ApkInstaller.java          APK 安装（单包 / install-multiple / Session）
    ├── FileLister.java            目录列举（root ls / File API）
    └── Prefs.java                 主题模式持久化
```

## 版本

- v1.0：应用列表 + 四大组件 + root 启动器
- v1.1：新增卸载 / 移除应用（常规 + Root 静默 + 禁用 + 恢复），回到列表自动刷新
- v1.2：新增长按提取 APK 到 Download 目录
- v1.3：
  - 修复 Android 15/16 edge-to-edge 下内容与状态栏重叠（布局 `fitsSystemWindows`）
  - 新增主题设置：日间 / 夜间 / 跟随系统（`values-night` + DayNight 主题，即时生效）
  - 新增文件浏览器：Root 下可浏览 `/data`、`/system` 等目录，点击即装 APK
  - 新增 APKS / APKM / XAPK 解析合并安装（root `pm install-multiple` 或系统安装器）
  - 新增「导出为 XAPK」：把已安装应用 base + split 重新打包分享
