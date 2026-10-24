# 来源与许可说明（NOTICE）

本仓库是 **dsh-pet** 的 Android 原生移植工程，不是上游项目本身。

## 上游项目

| 项 | 内容 |
|---|---|
| 名称 | dsh-pet（DSH 平台桌面宠物插件） |
| 仓库 | https://github.com/PC2005-cloud/dsh-pet |
| 许可 | MIT License |
| 版权 | Copyright (c) 2026 PC2005-cloud |

本仓库中的以下内容来自上游项目（含必要修改），按 MIT 协议使用：

- `renderer/src/**` —— 渲染层全部 TypeScript 源码（宠物渲染、物理、菜单、气泡、姿势、进度、配置解析等）
- `app/src/main/assets/webm/**`、`assets/pic/**`、`assets/memes/**`、`assets/fonts/**` —— 宠物动画视频、图片、表情与字体素材
- `app/src/main/assets/config.jsonc`、`assets/index.html` —— 运行时配置与页面壳

## 本仓库新增内容

同样以 MIT 协议发布：

- `app/src/main/java/com/dsh/pet/**` —— Kotlin 平台桥接层（悬浮窗 / 触摸层 / 无障碍覆盖层 / 文件持久化 / 模型调用 / 通知 / 设置页）
- `renderer/android/**` —— WebView 渲染层入口与 JS 宿主（fetch 拦截、配置加载、触摸层矩形上报）
- `renderer/build.mjs` —— 渲染层打包脚本（esbuild → 单文件 bundle）
- 构建脚本、文档

## 协议合规说明

MIT 协议要求「在软件的所有副本或实质部分中保留上述版权声明与许可声明」。因此：

1. 仓库根目录 `LICENSE` 同时保留上游版权行 `Copyright (c) 2026 PC2005-cloud` 与移植方版权行。
2. 渲染层打包产物 `app/src/main/assets/dsh-pet.js` 末尾通过 esbuild `legalComments: 'eof'` 保留了被内联依赖（含上游代码）的 MIT 版权注释，**请勿在再分发时删除该段**。
3. 若你基于本仓库再发布，请继续保留上述版权与许可声明。

## 第三方依赖

| 依赖 | 许可 |
|---|---|
| React / React-DOM 18 | MIT |
| esbuild | MIT |
| TypeScript | Apache-2.0 |
| AndroidX (core-ktx / appcompat / activity-ktx / webkit) | Apache-2.0 |
| OkHttp 4 | Apache-2.0 |
| Gradle / AGP | Apache-2.0 |

各依赖的完整许可文本见其官方仓库。
