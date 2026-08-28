# LOS 小窗 (LOSFreeform)

针对 **LineageOS 23.2（基于 Android 16 / API 36）** 优化的小窗（自由窗口 / Freeform Window）Xposed 模块。

> 项目基于 [reYAMF](https://github.com/JuanArton/reYAMF)（其又是 [YAMF](https://github.com/duzhaokun123/YAMF) 的分支）
> 开发而成。reYAMF 已适配 Android 13 ~ 16，与 LineageOS 23.2 的系统服务 API 完全匹配。

## 设计目标

LineageOS 默认**只在平板设备上启用** Freeform Window 特性（见
`vendor_lineage` 的 `config: tablet: Enable freeform windows by default`），
手机设备上没有官方小窗能力。本模块不依赖系统 Freeform 特性，而是通过在
`system_server` 中注入（Xposed）+ 创建**虚拟显示器（VirtualDisplay)** +
**悬浮窗（Overlay）**，把任意应用"搬"进一个可拖拽、可缩放的小窗里。

## 特性

- 把当前应用一键收进小窗（快速设置磁贴 / 广播 / 无障碍手势）
- 从最近任务（Recents）应用图标、任务栏（Taskbar）、桌面应用图标长按启动小窗
- 多实例：同一应用可同时开多个小窗
- 小窗可拖动、缩放（右下角拖拽点）、旋转、挂起（minimize）、全屏还原
- 支持 FLAG_SECURE 应用（受保护内容也能正常显示在小窗内）
- 侧边栏：长按应用列表中的应用图标可添加到侧边栏快捷启动
- 可调密度（DP）：窗口缩小时内容自动适配，避免字体过大/错位
- 窗口圆角、控制栏配色等外观均可设置

## 工作原理（架构一览）

```
┌────────────────────────────────────────────────────────────┐
│ system_server  (LSPosed hook "System Framework")           │
│  HookServiceManager.addService("package")                   │
│     → 注册 uid observer，把 YAMFManager Binder 交给 App     │
│  Hook ActivityManagerService.systemReady                     │
│     → 初始化系统服务引用、读取配置、注册广播                 │
│  Hook BroadcastController.checkBroadcastFromSystem(*)        │
│  Hook InputMonitor.requestFocus → 追踪当前前台 Display       │
└────────────────────────────────────────────────────────────┘
        │ 收到广播 / QS 磁贴 / 侧边栏指令
        ▼
┌────────────────────────────────────────────────────────────┐
│ 小窗容器 (App 进程，TYPE_APPLICATION_OVERLAY 悬浮窗)        │
│   1. DisplayManager.createVirtualDisplay(...)               │
│      → 为小窗创建虚拟显示器                                 │
│   2. SurfaceView/TextureView 渲染该虚拟显示器内容           │
│   3. IActivityTaskManager.moveRootTaskToDisplay(display)    │
│      或 startActivityAsUser + setLaunchDisplayId           │
│      → 把目标应用移入虚拟显示器（= 进入小窗）               │
│   4. 触摸/按键事件通过 InputManager.injectInputEvent       │
│      注入到虚拟显示器（setDisplayId），实现小窗内交互       │
└────────────────────────────────────────────────────────────┘
```

## 与 LineageOS 23.2 (Android 16) 的适配验证

针对 `lineage-23.2` 分支（`android-16.0.0_r4`）源码核实过的 hook 点：

| Hook 点 | LineageOS 23.2 状态 |
|---|---|
| `com.android.server.am.ActivityManagerService#systemReady` | ✅ 存在，签名 `(Runnable, TimingsTraceAndSlog)`，按方法名匹配 |
| `com.android.server.am.BroadcastController#checkBroadcastFromSystem` | ✅ 存在（Android 15 起从 AMS 拆到 BroadcastController，已双保险） |
| `com.android.server.wm.InputMonitor#requestFocus(IBinder, String)` | ✅ 存在，用于追踪前台 Display |
| `IActivityTaskManager#moveRootTaskToDisplay` | ✅ 通过 rikka-hidden-stub 编译 |
| compileSdk / targetSdk | ✅ 36 (Android 16) |

## 环境要求

- **LineageOS 23.x**（23.2 为当前验证目标，Android 16 / API 33+ 理论均可）
- **Root**：Magisk（含 Zygisk）或 KernelSU
- **LSPosed**：使用支持 Android 16 的分支，例如
  [JingMatrix/LSPosed](https://github.com/JingMatrix/LSPosed)（Zygisk 版）
- 无需 GApps、无需 Shizuku

## 构建（GitHub Actions，无需本地环境）

仓库已内置 `.github/workflows/build.yml`，推送到 GitHub 即可自动编译：

1. 把本项目推送到你的 GitHub 仓库（新建仓库后）：
   ```bash
   git remote add origin https://github.com/<你的用户名>/los-freeform.git
   git push -u origin main
   ```
2. 打开仓库页面 **Actions** 标签，等待 `Build APK` 工作流跑完。
3. 在构建结果的 **Artifacts** 下载 `losfreeform-apk`，解压得到：
   - `app-release.apk`（可直接安装）
   - `app-debug.apk`

> 每次推送/PR（`main`,`master` 分支）都会触发构建；
> 打 tag（如 `v1.0.0`）会自动生成 GitHub Release 并附带 APK。
> Release 产物目前使用 **debug 签名**（CI 内置），方便直接安装；
> 正式发布时请在 `app/build.gradle.kts` 里配置你自己的签名。

## 安装与启用

1. 安装 APK（`app-release.apk`）。
2. 打开 LSPosed → 模块 → 勾选 **LOS 小窗**，作用域勾选：
   - **System Framework**（必需，核心注入）
   - **System UI**（可选，部分交互）
   - **Launcher**（可选，桌面/最近任务图标挂钩，用于在图标上启动小窗）
3. 系统设置 → 无障碍 → 开启 **LOS 小窗** 无障碍服务（用于"当前应用→小窗"等操作）。
4. **重启**系统。

### 使用方式

- 快速设置（QS）磁贴：**新建小窗**、**当前应用进小窗**、**重置全部小窗**。
- 桌面：长按应用图标 → 选择 **Freeform**；在侧边栏长按应用添加常用应用。
- 最近任务：点击应用图标弹出的小窗按钮。
- 应用外广播：`com.xiaochuang.freeform.action.CURRENT_TO_WINDOW`（把当前应用收起为小窗）。

## 常见问题

- **系统崩溃/重启**：Xposed 模块的通病——安装的 APK 版本与注入的 system_server
  中缓存的版本不一致时系统可能崩溃。升级/降级模块后**务必重启系统**。
- **某些应用无法在小窗中启动/显示异常**：部分应用禁止多实例或对尺寸敏感，
  可在设置里调整"Reduce DPI"（建议 50~100 之间）。
- **小窗内画面显示为黑屏**：设置里切换 View Type（SurfaceView/TextureView）。
- **圆角异常**：可在设置中把窗口圆角调为 0。

## 已知限制

- 需要 root（LSPosed 注入，不支持免 root）。
- 部分应用在缩放过程中会重新加载。
- 与同源的 YAMF/Mi-FreeForm 一样，对"Chrome 多窗口"这类特殊场景处理不佳。

## 本项目文件结构

```
los-freeform/
├── .github/workflows/build.yml   # GitHub Actions 自动编译
├── app/                          # 模块主工程（app + xposed 注入）
│   └── src/main/
│       ├── aidl/                 # 跨进程接口 (IYAMFManager 等)
│       ├── assets/xposed_init    # Xposed 入口声明
│       └── java/com/xiaochuang/freeform/
│           ├── manager/          # App UI：设置、侧边栏、QS 磁贴、无障碍
│           └── xposed/           # system_server 注入：HookSystem/AppWindow 等
├── android-stub/                 # 编译期系统 hidden API 占位
└── gradle/libs.versions.toml     # 依赖版本目录
```

## 致谢

- [reYAMF (JuanArton)](https://github.com/JuanArton/reYAMF)
- [YAMF (duzhaokun123)](https://github.com/duzhaokun123/YAMF)
- [Mi-FreeForm (sunshine0523)](https://github.com/sunshine0523/Mi-FreeForm)
- [EzXHelper](https://github.com/KyuubiRan/EzXHelper) / [LSPosed](https://github.com/LSPosed/LSPosed)
- [rikka-dev / hidden-api](https://github.com/RikkaApps)

## License

GPL-3.0（继承自上游 YAMF / reYAMF）