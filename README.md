# dsh-pet-android

把 [dsh-pet](https://github.com/PC2005-cloud/dsh-pet)（DSH 平台的桌面宠物插件）移植成独立 Android 应用：桌面上飘着一只会动、会说话、能拖拽的宠物。

渲染逻辑（宠物动画、物理、气泡、菜单）直接复用上游 Web 代码，Kotlin 只做平台桥接——悬浮窗、触摸层、文件持久化、模型调用、系统通知。

- 应用显示名：**蓝色大肥鱼**
- 包名：`com.dsh.pet`
- 最低版本：Android 8.0（API 26），目标 API 35

## 功能

- **桌面悬浮宠物**：WebM 动画循环播放，重力/碰撞/惯性拖拽，松手后自动落回屏幕底部
- **多宠物**：最多 3 只同屏，各自独立移动、互不重叠
- **点击反馈**：点击音效 + 随机台词气泡 + 得分飘字
- **碎碎念**：定时从台词库随机冒泡，台词库可在设置页自定义
- **AI 对话**：填 OpenAI 兼容接口（地址 / 模型 / API Key）即可聊天，回复以气泡呈现
- **外观可调**：显示大小（40%~90% 屏宽）、透明度（30%~100%）、宠物数量
- **完全体模式**：透明度真到 100% 且不影响桌面点击（见下节）
- **通知栏控制**：隐藏 / 显示 / 停止桌宠

## 架构

```
┌─────────────────────────────────────────────┐
│ Kotlin 层（app/src/main/java/com/dsh/pet）   │
│  PetService      悬浮窗 + 触摸层 + 生命周期   │
│  PetA11yService  空壳无障碍服务（提供可信Context）│
│  SettingsActivity 设置页                     │
│  NativeBridge    JS ↔ Kotlin 桥              │
│  LlmClient       OpenAI 兼容调用             │
│  Store           原子写 JSON 持久化           │
└────────────────┬────────────────────────────┘
                 │ WebView + WebViewAssetLoader
┌────────────────▼────────────────────────────┐
│ 渲染层（renderer/，打包成 assets/dsh-pet.js）│
│  android/entry.ts  挂载 React 根             │
│  android/host.ts   fetch 拦截 / 配置加载 /    │
│                    触摸层矩形上报             │
│  src/client/pet.ts 宠物渲染与物理（来自上游） │
└─────────────────────────────────────────────┘
```

触摸层的设计要点：**每个命中元素一个独立窗口**，而不是「一个窗口覆盖所有命中区域的并集」。后者会被并集撑成横跨整屏的大带子，把桌面事件全吃掉（表现就是"退到桌面只能操作桌宠"）。

## 目录结构

```
.
├── app/                          Android 应用模块
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/dsh/pet/     Kotlin 平台层
│       ├── res/                  图标、字符串、无障碍配置
│       └── assets/               运行时素材与打包好的 dsh-pet.js
├── renderer/                     渲染层源码（TypeScript）
│   ├── android/                  WebView 入口与 JS 宿主
│   ├── src/                      上游渲染逻辑（client / shared / host …）
│   ├── build.mjs                 esbuild 打包脚本
│   └── tsconfig*.json
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── LICENSE                       MIT
└── NOTICE.md                     来源与许可说明
```

## 构建

### 环境

- JDK 17
- Android SDK Platform 35 + Build-Tools 35.0.0
- Gradle 8.9（AGP 8.5.2 要求 ≥ 8.7）

### 出 APK

`app/src/main/assets/dsh-pet.js` 已经是打包好的渲染层，**只改 Kotlin 时不需要 Node**：

```bash
# 1. 指向本机 SDK
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 2. 编译
gradle :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

### 改渲染层

改 `renderer/src` 或 `renderer/android` 后必须重新打包，否则 APK 里跑的还是旧 JS：

```bash
cd renderer
npm install                 # esbuild / react / react-dom / typescript
node build.mjs              # 产出 dist/dsh-pet.js
cp dist/dsh-pet.js ../app/src/main/assets/dsh-pet.js
cd .. && gradle :app:assembleDebug
```

`build.mjs` 里有一条关键的重定向：`src/client/pet.ts` 中 `import { petBridge } from './settings'` 会被指向 `android/petbridge.ts`。原因是上游 `settings.ts` 是 DSH 平台设置页组件，依赖平台 primitives，不能进 WebView bundle。

## 关于「完全体模式」

Android 12 起，系统会读取 `Settings.Global.maximum_obscuring_opacity_for_touch`（多数机型约 0.78）限制悬浮窗不透明度：窗口再高就会被判定为"遮挡桌面"，落到桌面的触摸会被直接丢弃。所以普通悬浮窗的透明度上限不是应用自己写死的，是系统不允许。

突破方式是把渲染窗口挂成**无障碍覆盖层**（`TYPE_ACCESSIBILITY_OVERLAY`）——这类窗口被系统视为可信，不受该阈值约束，可以 100% 不透明且桌面照常可点。

实现要点：

- `PetA11yService` 是一个**空壳**无障碍服务，只用于拿到 `AccessibilityService` 类型的 Context。它不读取屏幕内容（`canRetrieveWindowContent="false"`）、不模拟点击、不收集任何信息。
- 开关在设置页「外观」卡片；打开时若无障碍未授权，会 Toast 提示并跳转系统无障碍设置页。
- 窗口类型在 `addView` 之后无法修改，所以切换开关会重建全部窗口（宠物位置会回到默认）。
- 若 ROM 不允许无障碍覆盖层，`addView` 抛出的异常会被捕获并自动回退普通悬浮窗，透明度回到系统上限，功能不中断。

## 权限

| 权限 | 用途 |
|---|---|
| `SYSTEM_ALERT_WINDOW` | 桌宠显示在桌面之上（必需） |
| `FOREGROUND_SERVICE` | 前台服务维持桌宠运行 |
| `POST_NOTIFICATIONS` | 对话完成等通知 |
| `INTERNET` | AI 接口调用 |
| `VIBRATE` | 触觉反馈 |
| 无障碍服务（可选） | 仅用于完全体模式，见上节 |

## 已知限制

- 无障碍覆盖层在部分定制 ROM 上会被拒绝，此时自动回退到普通悬浮窗（透明度上限约 78%）
- 切换完全体模式会重建窗口，宠物位置重置
- 仓库含约 60MB 动画素材（WebM），clone 体积较大；APK 约 65MB
- 渲染层源码来自上游，其中 `src/app`、`src/standalone` 是 DSH 平台版本，不参与 Android 构建

## 发布

构建产物（APK / AAB）不进仓库，见 `.gitignore`。可直接安装的调试包请在 **Releases** 下载，或按上面的步骤自行构建。

```bash
gradle :app:assembleDebug
# 输出：app/build/outputs/apk/debug/app-debug.apk（约 65MB，主要体积是 WebM 动画素材）
```

## 许可

MIT License。上游 dsh-pet 版权归 PC2005-cloud 所有，本移植工程在此基础上新增 Android 平台层。

再分发时请保留 `LICENSE` 中的版权声明，以及 `assets/dsh-pet.js` 末尾的版权注释段（esbuild 已通过 `legalComments: 'eof'` 保留）。详见 [NOTICE.md](NOTICE.md)。

> 上游项目声明：素材（动画 / 提示词 / 源视频）仅限开源使用，禁止商用。
