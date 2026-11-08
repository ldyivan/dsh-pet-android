/**
 * 「吃白饭」Android 独立入口（与 dsh-pet.js 并行加载，不碰其内部闭包）。
 *
 * Android 版 dsh-pet.js 不提供页面内 `petApi7340` 服务（Cordis 未挂载），
 * 所以 pose / progress 改为从共享 DOM 自读（假 ctx），不依赖宿主 provide。
 *
 * 两条触发入口（共用同一演出实例）：
 *   1) 通知栏「吃白饭」按钮：Kotlin evaluateJavascript 调 window.__dshBaifanStart(petId?)
 *   2) 长按菜单「吃白饭」项：**直接往原生长按菜单 DOM 注入**（见 injectMenuItems）。
 *      为什么不走 /menu 注册表：shipped android/host.ts 的 /menu 三端点是**空壳**
 *      （POST /menu/item 直接 return {ok:true}，不存注册表、不 writeSection('menu')），
 *      长按菜单端（pet.ts）读的是 /state 的 sections.menu 快照——host 从不写它，
 *      所以原生「更多」分支永远为空、第三方注册全打在空壳上。
 *      改 shipped host 塞真注册表 = 必须重编译 dsh-pet.js = 冲掉睡眠补丁（红线），
 *      因此用 DOM 注入这条路：零 host 改动、不碰 dsh-pet.js、与独立叠层架构一致。
 *
 * 降级说明：blob URL 无法反推动画名，progress.playbackId 固定返回 null。
 *   · clipMs      → 5 200 ms 兜底
 *   · keepWarm    → 1 800 ms 后返回 true
 *   · waitPlayStarted → 300 ms 超时返回
 *   · sidePlaying → 永远 false，看门狗每 400 ms 补一次（功能可用，节奏略粗）
 * 核心等待 waitPlayback 走 host 端点 /playback/{id}?wait=，精确不受降级影响。
 *
 * 睡眠拒绝：启动前 GET /state 读 pets[petId].anim.data.name，命中「睡觉」即拒绝。
 *   睡眠态全在 dsh-pet.js 闭包内，Kotlin 侧不持有，所以探针必须放这里。
 */
import { activityAssets } from '../src/client/baifan/assets.ts';
import { CSS, LABEL, OWNER, ROOT_ID, STYLE_TAG } from '../src/client/baifan/constants.ts';
import { createScene } from '../src/client/baifan/scene.ts';
import { createShow, type Show } from '../src/client/baifan/show.ts';
import { createTransport } from '../src/client/baifan/transport.ts';

/** 接口基址（/state 与 /config 是 shipped host 的真端点；/menu 是空壳，此处不用）。 */
const ROUTE = '/dsh-pet-7340';
/** 注入到长按菜单的项上打的标记（避免同一棵菜单重复注入）。 */
const INJECTED_MARK = 'data-baifan-item';
const sleep = (ms: number): Promise<void> => new Promise((resolve) => window.setTimeout(resolve, ms));

// ---------------------------------------------------------------- 假 ctx

function findPetRoot(): HTMLElement | null {
  // 单宠场景：页面上唯一的 .dsh-pet-root 即目标
  return document.querySelector('.dsh-pet-root') as HTMLElement | null;
}

function findFrontVideo(): HTMLVideoElement | null {
  // 前台 video（is-front 标记的那一段）
  return document.querySelector('video.dsh-pet-video.is-front') as HTMLVideoElement | null;
}

/**
 * 造一个假 ctx：get('petApi7340') 返回 DOM 自读的 pose / progress。
 */
function createFakeCtx(): { get: (name: string) => unknown } {
  return {
    get(name: string): unknown {
      if (name !== 'petApi7340') return undefined;
      return {
        pose: (_petId: string): { x: number; y: number; facing: 'left' | 'right' } | null => {
          const root = findPetRoot();
          if (!root) return null;
          const rect = root.getBoundingClientRect();
          if (rect.width < 10 || rect.height < 10) return null;
          const x = Math.min(1, Math.max(0, (rect.left + rect.width / 2) / window.innerWidth));
          const y = Math.min(1, Math.max(0, (rect.top + rect.height / 2) / window.innerHeight));
          const facing: 'left' | 'right' =
            root.getAttribute('data-facing') === 'left' ? 'left' : 'right';
          return { x, y, facing };
        },
        progress: (_petId: string): {
          playbackId: string | null;
          currentTime: number;
          duration: number;
          paused: boolean;
          anim?: string;
        } | null => {
          const video = findFrontVideo();
          if (!video) return null;
          return {
            playbackId: null,
            currentTime: video.currentTime || 0,
            duration: video.duration || 0,
            paused: video.paused,
          };
        },
      };
    },
  };
}

// ---------------------------------------------------------------- 演出状态
/** 演出状态 */

let show: Show | null = null;
/** 灭屏自动暂停标记（亮屏恢复用）。 */
let baifanAutoPaused = false;

/**
 * chase 阶段视频循环控制：消除侧身跑接力间的微顿（1.2s 从头重播的间隙）。
 * 每 500ms 检查一次：isChasing() 为 true 时设前台 video.loop=true（连续播），
 * 离开 chase 相位后复位 loop=false（让吃/期待等一次性动画正常播完）。
 *
 * 朝向翻转：loop 模式下视频自行循环、不再触发 dsh-pet.js 的 .play()（它只在换源/重播
 * 时设一次 transform 翻转），于是「左右疾跑不转头、一直朝右」的根因就在这里。
 * 这里按 show.facing() 每 tick 直接驱动前台 video 的水平翻转，约定与 dsh-pet.js 完全一致
 * （素材默认朝左，朝右跑 → scaleX(-1)），离开 chase 时清回（交由 dsh-pet.js 管理非 chase 朝向）。
 */
let chaseLoopTimer = 0;
function startChaseLoopControl(): void {
  if (chaseLoopTimer !== 0) return;
  chaseLoopTimer = window.setInterval(() => {
    const s = show;
    if (s === null) return;
    const video = findFrontVideo();
    if (!video) return;
    if (s.isChasing()) {
      video.loop = true;
      // chase 循环播放不重走 dsh-pet.js 的翻转逻辑，这里按其 facing 约定补齐水平翻转
      video.style.transform = s.facing() === 'right' ? 'scaleX(-1)' : '';
    } else {
      video.loop = false;
      video.style.transform = '';
    }
  }, 500);
}

/**
 * 长按菜单超屏兜底：原生 mountContextMenu 在 Android 没传 clamp（缺省整视口），
 * 菜单项多 + 宠物在底部时根面板夹到顶后底部溢出。纯 CSS 叠层夹回视口内——
 * 给菜单**列**（.dsh-pet-menu-column，position:absolute、有真实尺寸）设 max-height +
 * overflow-y:auto：被夹住后高度封顶、超出项内部滚动。
 *
 * **绝不能在根容器 .dsh-pet-menu 上设 overflow**：它是 position:fixed;left:0;top:0 的
 * 0×0 容器，子列是 absolute 定位；给 0×0 元素设任意 overflow 会形成以 (0,0) 为基准的
 * 裁剪盒，把所有绝对定位的菜单列裁掉 → 整个长按菜单消失（上一版正是踩了这个坑）。
 * 不碰 dsh-pet.js（改了要重编译、冲睡眠补丁）。
 */
const MENU_GUARD_CSS =
  '.dsh-pet-menu-column{max-height:calc(100vh - 16px);max-width:calc(100vw - 16px);' +
  'overflow-y:auto}';

function injectCss(): void {
  if (document.querySelector('style[data-plugin-css="' + STYLE_TAG + '"]') !== null) return;
  const tag = document.createElement('style');
  tag.dataset.plugin = OWNER;
  tag.dataset.pluginCss = STYLE_TAG;
  tag.textContent = CSS + MENU_GUARD_CSS;
  document.head.appendChild(tag);
}
function ensureShow(): Show {
  if (show !== null) return show;
  const ctx = createFakeCtx();
  injectCss();
  const layer = document.createElement('div');
  layer.id = ROOT_ID;
  const toastEl = document.createElement('div');
  toastEl.className = 'bf-toast';
  layer.appendChild(toastEl);
  document.body.appendChild(layer);
  const scene = createScene(layer, toastEl);
  const transport = createTransport(ctx);
  show = createShow({ transport, scene });
  startChaseLoopControl();
  return show;
}

// ---------------------------------------------------------------- 探针 / 配置

/**
 * 睡眠拒绝探针（统一版）：
 * 1) 先查 DOM 长按菜单里是否有「叫醒」项（dsh-pet.js 只在睡眠态渲染「叫醒」，最可靠）；
 * 2) 再 GET /state 读 pets[petId].anim.data.name 命中「睡觉」。
 * 两个源取 OR：任一命中即拒绝。过渡态（醒来动画播到一半）时「叫醒」项可能已消失但 /state 仍短暂保留旧值，
 * 以菜单项为准（用户可见状态）优先，/state 做兜底。
 */
async function isPetSleeping(petId: string): Promise<boolean> {
  // 主判据：菜单里有「叫醒」项（dsh-pet.js 只在 sleeping.has() 时渲染）
  const menu = document.querySelector('.dsh-pet-menu');
  if (menu !== null) {
    const items = menu.querySelectorAll('.dsh-pet-menu-item');
    for (const it of items) {
      const el = it as HTMLElement;
      if (el.getAttribute(INJECTED_MARK) === '1') continue;
      const label = (el.querySelector(':scope > span') as HTMLElement | null)?.textContent?.trim();
      if (label === '叫醒') return true;
    }
  }
  // 兜底：/state 读 anim 叶子
  try {
    const res = await fetch(ROUTE + '/state');
    const state: Record<string, unknown> = await res.json();
    const pets = (state as Record<string, unknown>)['pets'] as
      | Record<string, Record<string, { data?: Record<string, unknown> }>>
      | undefined;
    const leaf = pets?.[petId]?.['anim']?.data;
    if (leaf && typeof leaf.name === 'string' && /睡觉/.test(leaf.name)) {
      return true;
    }
    return false;
  } catch {
    return false;
  }
}

/**
 * 从 /config 按条目找 owns petId 的那份，取 animations.baifan 的 5 个动画名。
 * host /config 返回**按条目**结构（cfg.<条目名> = { pets, animations, ... }），
 * 与上游 baifan.ts 的 launch 遍历口径一致（单宠场景条目 = main）。
 */
async function getBaifanAnimNames(petId: string): Promise<string[] | null> {
  try {
    const res = await fetch(ROUTE + '/config');
    const cfg: Record<string, unknown> = await res.json();
    for (const raw of Object.values(cfg)) {
      if (!raw || typeof raw !== 'object') continue;
      const conf = raw as Record<string, unknown>;
      const pets = conf.pets;
      if (!Array.isArray(pets)) continue;
      const owns = pets.some(
        (item) => item !== null && typeof item === 'object' && (item as Record<string, unknown>).id === petId,
      );
      if (!owns) continue;
      const animations = conf.animations as Record<string, unknown> | undefined;
      const names = animations?.['baifan'];
      if (Array.isArray(names)) {
        return names.filter((n): n is string => typeof n === 'string');
      }
    }
    return null;
  } catch {
    return null;
  }
}

/**
 * 把吃白饭运行状态上报给原生：通知栏「吃白饭/停止吃白饭」按钮标签据此动态切换。
 * Kotlin 侧 setBaifanRunning 幂等（同状态不重刷通知），这里按当前 isRunning 上报即可。
 */
function reportBaifanState(): void {
  try {
    const on = show !== null && show.isRunning();
    const native = (window as any).DshNative;
    if (native && typeof native.setBaifanRunning === 'function') native.setBaifanRunning(on);
  } catch {
    /* NativeBridge 不可用时静默 */
  }
}

/** 启动一场演出（睡眠拒绝 + 读配置 + 开演）。菜单点击与通知栏入口共用。
 *  @param force - 为 true 时跳过睡眠守卫（菜单路径已在点击前手动触发原生「叫醒」，此刻正处醒来过渡，
 *                 守卫的「睡觉-起来」过渡态会误拒，故绕过）。通知栏路径不带 force，保留守卫。 */
async function launch(petIdRaw: string, force = false): Promise<void> {
  const id = typeof petIdRaw === 'string' && petIdRaw !== '' ? petIdRaw : 'main';
  const s = ensureShow();
  if (s.isRunning()) return;
  if (!force && (await isPetSleeping(id))) {
    console.log('[baifan] 宠物在睡觉，拒绝启动');
    return;
  }
  const names = await getBaifanAnimNames(id);
  if (names === null) {
    console.warn('[baifan] config.animations.baifan 缺失，无法启动');
    return;
  }
  s.start(id, activityAssets(names));
  reportBaifanState(); // 开演 → 通知栏按钮标签切「停止吃白饭」
}

// ---------------------------------------------------------------- 长按菜单 DOM 注入

/**
 * 关闭原生长按菜单：合成一个「菜单外」的 mousedown，走 menu.ts 的 onDocPointerDown
 * （capture 捕获、root.contains(document)=false → collapse/close）。
 * 若当前展开过子菜单（openChild 非空），一次只收最深一级，短轮询补发直到菜单消失。
 * 复用项目既有机制（Kotlin 侧 injectMenuOutsideClick 同款），不新增任何常驻 listener。
 */
function closeMenu(times: number): void {
  if (document.querySelector('.dsh-pet-menu') === null || times > 6) return;
  try {
    document.dispatchEvent(
      new MouseEvent('mousedown', { bubbles: true, cancelable: true, view: window }),
    );
  } catch {
    return;
  }
  window.setTimeout(() => closeMenu(times + 1), 60);
}

/**
 * 找同级菜单里 dsh-pet.js 渲染的「睡觉/叫醒」项（睡眠态动态文案，二者择一）。
 * 它是 dsh-pet.js 内部按 Ie.sleeping.has() 渲染的，点击 handler 也在 dsh-pet.js 内注册——
 * 是外部做「吃白饭↔睡觉互斥」的零改动锚点：点它就能触发原生 入睡/醒来。
 */
function findSleepItem(rootPanel: Element): HTMLElement | null {
  const items = rootPanel.querySelectorAll('.dsh-pet-menu-item');
  for (const it of items) {
    const el = it as HTMLElement;
    if (el.getAttribute(INJECTED_MARK) === '1') continue; // 跳过我自己的
    const label = (el.querySelector(':scope > span') as HTMLElement | null)?.textContent?.trim();
    if (label === '睡觉' || label === '叫醒') return el;
  }
  return null;
}

/**
 * 把「吃白饭」项插到「睡觉/叫醒」项之后（睡觉 与 动作 之间）；找不到则追加列尾。
 * 文案随玩法状态动态切换（统一入口进出）：未开演 = 「吃白饭」（点击开演）；进行中 = 「停止吃白饭」（点击收场）。
 *
 * 吃白饭 ↔ 睡觉互斥（都走 dsh-pet.js 原生项的点击，零 host/零重编译）：
 *   · 点「吃白饭」且宠在睡（同级有「叫醒」项）→ 先 click()「叫醒」项触发原生醒来，再开演；
 *   · 给「睡觉」项并挂监听：点它时自动 show.halt()，停掉吃白饭。
 * 注入后重算根面板 top：原生定位按注入前高度算 top，加了我这行会溢出屏底，夹回视口内。
 */
function injectIntoMenu(rootMenu: HTMLElement): void {
  // 根面板 = 第一个可见的 .dsh-pet-menu-column（mountContextMenu 里 rootPanel.display=block）
  const panels = rootMenu.querySelectorAll('.dsh-pet-menu-column');
  let rootPanel: Element | null = null;
  for (const p of panels) {
    if ((p as HTMLElement).style.display !== 'none') {
      rootPanel = p;
      break;
    }
  }
  if (rootPanel === null) rootPanel = panels.length > 0 ? panels[0] : null;
  if (rootPanel === null) return;
  // 防重复：这棵菜单里已有我的项就不再注
  if (rootPanel.querySelector('[' + INJECTED_MARK + ']') !== null) return;

  const running = show !== null && show.isRunning();
  const text = running ? '停止吃白饭' : LABEL;
  const sleepItem = findSleepItem(rootPanel);

  const item = document.createElement('div');
  item.className = 'dsh-pet-menu-item';
  item.setAttribute(INJECTED_MARK, '1');
  const label = document.createElement('span');
  label.textContent = text;
  item.appendChild(label);
  item.addEventListener('click', (e: Event) => {
    e.preventDefault();
    e.stopPropagation();
    if (running) {
      // 停止入口：收场
      if (show !== null) void show.halt('用户停止');
      reportBaifanState(); // 收场 → 通知栏按钮标签切回「吃白饭」
      closeMenu(0);
      return;
    }
    // 与睡觉互斥：此刻宠在睡（同级有「叫醒」项）→ 先触发原生叫醒（自动醒来），
    // 留一拍让「睡觉-起来」动画起势，再开吃（避免两种动画打架）；force 绕过醒来过渡态守卫
    const sp = sleepItem?.querySelector(':scope > span')?.textContent?.trim();
    if (sp === '叫醒' && sleepItem !== null) {
      sleepItem.click(); // 触发 dsh-pet.js 内部 wake（会顺手关掉菜单）
      window.setTimeout(() => {
        void launch('main', true);
      }, 600);
      return;
    }
    // 平时态：直接开吃
    void launch('main');
    closeMenu(0);
  });

  // 位置：插到「睡觉/叫醒」项之后（睡觉 与 动作 之间）；找不到则追加到列尾
  if (sleepItem !== null && sleepItem.parentNode === rootPanel) {
    rootPanel.insertBefore(item, sleepItem.nextSibling);
  } else {
    rootPanel.appendChild(item);
  }

  // 互斥另一向：点原生「睡觉」项时自动停吃白饭
  const spLabel = sleepItem?.querySelector(':scope > span')?.textContent?.trim();
  if (sleepItem !== null && spLabel === '睡觉') {
    sleepItem.addEventListener('click', () => {
      if (show !== null && show.isRunning()) void show.halt('睡觉中，已停止吃白饭');
      reportBaifanState();
    });
  }

  // 注入后重算根面板 top：原生定位按注入前高度算的 top，加了我这行会溢出屏底，夹回视口内
  const rp = rootPanel as HTMLElement;
  const top = parseFloat(rp.style.top);
  const newH = rp.offsetHeight;
  if (Number.isFinite(top) && top + newH > window.innerHeight - 8) {
    rp.style.top = Math.max(8, window.innerHeight - newH - 8) + 'px';
  }

  // 「穿透」项：插到列尾，点击切换穿透模式（触摸层收回 → 屏幕整块穿透给下层 App）
  const ctItem = document.createElement('div');
  ctItem.className = 'dsh-pet-menu-item';
  ctItem.setAttribute(INJECTED_MARK, '1');
  const ctLabel = document.createElement('span');
  ctLabel.textContent = '穿透';
  ctItem.appendChild(ctLabel);
  ctItem.addEventListener('click', (e: Event) => {
    e.preventDefault();
    e.stopPropagation();
    // 当前未穿透 → 开穿透；已穿透 → 关穿透（状态由 Kotlin 侧持有，这里只做 toggle）
    try {
      const native = (window as any).DshNative;
      if (native?.setClickThrough !== undefined) {
        native.setClickThrough(true);
      }
    } catch { /* silent */ }
    closeMenu(0);
  });
  rootPanel.appendChild(ctItem);

  // 穿透后重算 top（多了一行）
  const newH2 = rp.offsetHeight;
  if (Number.isFinite(top) && top + newH2 > window.innerHeight - 8) {
    rp.style.top = Math.max(8, window.innerHeight - newH2 - 8) + 'px';
  }

  console.log('[baifan] 长按菜单已注入「' + text + '」+「穿透」项' + (sleepItem !== null ? '（睡觉与动作之间）' : '（列尾）'));
}

let observerStarted = false;
/** 轮询兜底定时器（MutationObserver 偶发漏拍时的保险丝） */
let menuPollTimer = 0;

/**
 * 菜单注入轮询兜底：每 800ms 检查一次 .dsh-pet-menu 是否存在且未注入。
 * MutationObserver 是主通道，轮询是保险丝——某些 ROM 的 WebView 对
 * childList mutation 的派发偶发延迟或丢失，轮询保证 2 秒内一定能注入。
 */
function startMenuPolling(): void {
  if (menuPollTimer !== 0) return;
  menuPollTimer = window.setInterval(() => {
    const menu = document.querySelector('.dsh-pet-menu') as HTMLElement | null;
    if (menu === null) return;
    const already = menu.querySelector('[' + INJECTED_MARK + ']') !== null;
    if (!already) {
      injectIntoMenu(menu);
    }
  }, 800);
}

/** 盯 body 的直接子级：.dsh-pet-menu 一出现（长按打开）就注入；关闭时整棵移除，无需清理。 */
function startMenuObserver(): void {
  if (observerStarted) return;
  observerStarted = true;
  const check = (nodes: NodeListOf<HTMLElement>): void => {
    let target: HTMLElement | null = null;
    for (const n of nodes) {
      if (n.nodeType === Node.ELEMENT_NODE && n.matches('.dsh-pet-menu')) {
        target = n;
        break;
      }
    }
    if (target !== null) {
      // 根面板在下一帧建好，延一拍再注入
      window.setTimeout(() => injectIntoMenu(target), 0);
    }
  };
  const mo = new MutationObserver((muts) => {
    for (const mut of muts) {
      for (const n of mut.addedNodes) {
        if (n.nodeType === Node.ELEMENT_NODE && (n as HTMLElement).matches('.dsh-pet-menu')) {
          window.setTimeout(() => injectIntoMenu(n as HTMLElement), 0);
          return;
        }
      }
    }
    // 兜底：菜单根已挂好但子级 panel 才到（理论上 mountContextMenu 一次 append root 就带根面板）
    const menu = document.querySelector('.dsh-pet-menu');
    if (menu !== null && menu.querySelector('[' + INJECTED_MARK + ']') === null) {
      check(document.querySelectorAll('.dsh-pet-menu') as unknown as NodeListOf<HTMLElement>);
    }
  });
  mo.observe(document.body, { childList: true });
  // 轮询兜底：每 800ms 扫一次，防止 Observer 漏拍
  startMenuPolling();
}

// ---------------------------------------------------------------- 全局暴露

declare global {
  interface Window {
    /** 通知栏「吃白饭」按钮调用；petId 缺省 'main'。与长按菜单共用同一演出实例。 */
    __dshBaifanStart: (petId?: string) => Promise<void>;
    /** 用户停止当前演出（通知栏 / 菜单均可调）。 */
    __dshBaifanStop: () => void;
    /** 动态 toggle（通知栏单按钮用）：在跑→停、没跑→开。 */
    __dshBaifanToggle: () => Promise<void>;
    /** 灭屏自动暂停（Kotlin 屏幕广播 ACTION_SCREEN_OFF 调用）。 */
    __dshBaifanPause: () => void;
    /** 亮屏自动恢复（Kotlin 屏幕广播 ACTION_SCREEN_ON / USER_PRESENT 调用）。 */
    __dshBaifanResume: () => void;
    /** 穿透模式开关（Kotlin 角落按钮 / 菜单注入调用）。 */
    __dshClickThrough: (on: boolean) => void;
  }
}

window.__dshBaifanStart = (petId?: string) => launch(typeof petId === 'string' ? petId : 'main');

window.__dshBaifanStop = () => {
  if (show !== null) void show.halt('用户停止');
  reportBaifanState();
};

window.__dshBaifanToggle = async () => {
  const s = show;
  if (s !== null && s.isRunning()) {
    void s.halt('用户停止');
    reportBaifanState(); // 收场 → 「吃白饭」
  } else {
    await launch('main');
    reportBaifanState(); // 开演 → 「停止吃白饭」（launch 内部已报，这里兜底通知栏路径）
  }
};

window.__dshBaifanPause = () => {
  const s = show;
  if (s === null || !s.isRunning()) return;
  baifanAutoPaused = true;
  console.log('[baifan] 灭屏自动暂停吃白饭');
  void s.halt('灭屏暂停');
  reportBaifanState();
};

window.__dshBaifanResume = () => {
  if (!baifanAutoPaused) return;
  baifanAutoPaused = false;
  console.log('[baifan] 亮屏恢复吃白饭');
  void launch('main', true);
};

/** 穿透模式：调 Kotlin NativeBridge 控制触摸层收回，屏幕整块穿透给下层 App。 */
window.__dshClickThrough = (on: boolean) => {
  try {
    (window as any).DshNative?.setClickThrough(on);
    console.log('[baifan] 穿透模式 ' + (on ? '开' : '关'));
  } catch {
    // NativeBridge 不可用时静默（不影响其他功能）
  }
};

ensureShow();
startMenuObserver();
console.log('[baifan] 独立入口已挂载（长按菜单 DOM 注入 + 通知栏，睡眠中被拒，灭屏自动暂停）');