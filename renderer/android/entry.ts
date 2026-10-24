// Android WebView 渲染层入口。
// 职责只有三件事（与原 DSH 插件 app.ts 的装配层等价，但剥离所有平台壳）：
//   1) 从 react/react-dom 取运行时，组装 makePetUI → PetMulti
//   2) createRoot 挂到 DOM
//   3) startHost() 启动 WebView 内 JS 宿主（fetch 拦截 + 配置加载 + 碎碎念定时 + 触摸层 rAF）
// PetMulti 组件完全自包含（配置拉取/长轮询分发/arena 碰撞/样式注入都在 pet.ts 内部），
// 入口不向它传任何额外参数。
import { useEffect, useRef, useState } from 'react';
import { jsx as h } from 'react/jsx-runtime';
import { createRoot } from 'react-dom/client';
import { makePetUI } from '../src/client/pet';
import { startHost } from './host';
import { native } from './native';
import { petBridge } from './petbridge';

function boot(): void {
  // 挂载容器：PetMulti 内部渲染 .dsh-pet-root（position:fixed 铺满视口），
  // 容器本身不参与布局，仅作为 React 根。
  const container = document.createElement('div');
  container.id = 'dsh-pet-app';
  document.body.appendChild(container);

  // 点击回执（宠物被真正点一下，不是拖拽）：播点击音效 + 从台词库里随机弹一句。
  // 音效开关与台词库都在原生设置页里，读不到时安静降级。
  petBridge.clickFeedback = (petId: string): void => {
    native.clickSound();
    window.__dshSaying?.(petId);
  };
  const PetMulti = makePetUI({ h, useState, useEffect, useRef });
  const root = createRoot(container);
  root.render(h(PetMulti, {}));

  // 宿主初始化（异步）：先拦截 fetch 再加载配置，保证 PetMulti 首次拉取时路由已就绪。
  void startHost().catch((e: unknown) => {
    console.error('[dsh-pet] startHost 失败：' + (e instanceof Error ? e.message : String(e)));
  });
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', boot, { once: true });
} else {
  boot();
}
