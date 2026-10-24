/**
 * Android 宿主半侧（原 src/host 的等价实现，跑在 WebView 里）。
 *
 * 原项目的宿主是 Node 进程：读配置、合并校验、维护 /state、调模型、发通知。
 * Android 没有 Node，这里用同一份 shared 契约在 JS 侧重建**最小但形状一致**的宿主：
 *
 *   - 配置：内置 assets/config.jsonc + 用户层 main-config.jsonc（顶层整段替换，与原合并口径一致）
 *   - 状态：叶子 { counter, data } + 顶层 counter + 长轮询（与 openapi.yaml 完全同构）
 *   - 动作：whisper / chat / balance / anim / position / throw / lock / bubble-clear / pet-display
 *   - 素材：/thumb 转发到本地 webm 目录，/pic /font 由 WebViewAssetLoader 直接映射
 *   - 平台：模型调用、通知、触摸层跟随经 Kotlin 桥（native.ts）
 *
 * 原则沿用原项目：**能力缺失就显式失败**，绝不伪造余额或假装模型调用成功。
 */
import { native } from './native';
import { readPose } from '../src/client/pose';

type Leaf = { counter: number; data: unknown };
type State = {
  counter: number;
  sections: Record<string, Leaf>;
  pets: Record<string, Record<string, Leaf>>;
};

const API = '/dsh-pet-7340';

/* ------------------------------------------------------------------ JSONC */

/** 去注释（字符串内的 // 与 /* 不误伤） */
function stripJsonc(src: string): string {
  let out = '';
  let i = 0;
  let inStr = false;
  let inLine = false;
  let inBlock = false;
  while (i < src.length) {
    const c = src[i];
    const n = src[i + 1];
    if (inLine) {
      if (c === '\n') {
        inLine = false;
        out += c;
      }
      i++;
      continue;
    }
    if (inBlock) {
      if (c === '*' && n === '/') {
        inBlock = false;
        i += 2;
        continue;
      }
      i++;
      continue;
    }
    if (inStr) {
      out += c;
      if (c === '\\') {
        out += n ?? '';
        i += 2;
        continue;
      }
      if (c === '"') inStr = false;
      i++;
      continue;
    }
    if (c === '"') {
      inStr = true;
      out += c;
      i++;
      continue;
    }
    if (c === '/' && n === '/') {
      inLine = true;
      i += 2;
      continue;
    }
    if (c === '/' && n === '*') {
      inBlock = true;
      i += 2;
      continue;
    }
    out += c;
    i++;
  }
  // 去尾逗号（JSONC 允许，JSON.parse 不允许）
  return out.replace(/,(\s*[}\]])/g, '$1');
}

function parseJsonc(text: string, label: string): Record<string, unknown> {
  try {
    const v = JSON.parse(stripJsonc(text));
    if (!v || typeof v !== 'object' || Array.isArray(v)) throw new Error('顶层不是对象');
    return v as Record<string, unknown>;
  } catch (e) {
    // 原项目性格：配置写错要大声报错，不静默兜底
    console.error('[dsh-pet] ' + label + ' 解析失败：' + (e instanceof Error ? e.message : String(e)));
    return {};
  }
}

/* ----------------------------------------------------------------- config */

let builtinConfig: Record<string, unknown> = {};
let userConfig: Record<string, unknown> = {};
let configLoaded = false;

/** 宠物宽度默认占视口宽度的比例（设置页滑条可改） */
const PET_RATIO_DEFAULT = 0.42;
/** 用户可调上限：0.40~0.90（原生设置页滑条同区间，两侧必须同步） */
const PET_RATIO_MAX = 0.90;
const PET_RATIO_MIN = 0.40;

/**
 * UI 设置（原生设置页写 filesDir/ui-settings.json）。
 * 单独一份而不往 main-config.jsonc 里塞：那份是原项目契约（JSONC 文本，写错一个字符就整份配置归零），
 * 而这些是手机专属的显示偏好，不应该能把宠物弄没。
 */
type UiSettings = {
  /** 宠物宽度 / 视口宽度（0.40~0.90） */
  sizeRatio: number;
  /** 整体不透明度（0.2~1） */
  opacity: number;
  /** 点击音效 */
  clickSound: boolean;
  /** 同时显示几只（1~3） */
  petCount: number;
  /** 台词库（点击随机弹一句） */
  sayings: string[];
};
function uiSettings(): UiSettings {
  let raw: Record<string, unknown> = {};
  try {
    raw = JSON.parse(native.readUiSettings() || '{}') as Record<string, unknown>;
  } catch {
    raw = {};
  }
  const num = (v: unknown, d: number): number => (typeof v === 'number' && Number.isFinite(v) ? v : d);
  const sayings = Array.isArray(raw.sayings)
    ? (raw.sayings as unknown[])
        .filter((x): x is string => typeof x === 'string' && x.trim() !== '')
        .map((x) => x.trim())
    : [];
  return {
    sizeRatio: Math.min(PET_RATIO_MAX, Math.max(PET_RATIO_MIN, num(raw.sizeRatio, PET_RATIO_DEFAULT))),
    opacity: Math.min(1, Math.max(0.2, num(raw.opacity, 1))),
    clickSound: raw.clickSound !== false,
    petCount: Math.min(3, Math.max(1, Math.round(num(raw.petCount, 1)))),
    sayings,
  };
}
/**
 * Android 上 display 只有两种语义：显示 / 暂时隐藏。
 * 原项目的 web/desktop 是「浏览器页 / 桌面窗口」两种表面，手机上悬浮层就是桌面本身，
 * 两者无法区分（这正是「选了仅桌面就不见了」的根因：客户端按 isWebVisible 过滤）。
 * 因此这里统一归一：只有 'none' 隐藏，其余一律显示。
 */
const normDisplay = (d: unknown): string => (d === 'none' ? 'none' : 'both');
/**
 * 手机屏幕适配：
 *  1) size 一律按视口宽度重算（桌面端 462 是窗口 px，手机视口只有 360~430px）；
 *  2) display 归一（只有 none / 显示）；
 *  3) 按用户设置复制多只宠物（第 2~4 只换角落，避免重叠）。
 */
function adaptPetsForScreen(main: Record<string, unknown>): Record<string, unknown> {
  const vw = window.innerWidth;
  const ui = uiSettings();
  const list = Array.isArray(main.pets) ? (main.pets as Array<Record<string, unknown>>) : null;
  if (list === null) return main;
  const size = vw > 0 ? Math.max(96, Math.round(vw * ui.sizeRatio)) : 462;
  const pets: Array<Record<string, unknown>> = list.map((p) => ({
    ...p,
    size,
    display: normDisplay(p.display),
  }));
  const corners = ['bottom-right', 'bottom-left', 'top-left', 'top-right'];
  const base = pets[0];
  while (base !== undefined && pets.length < ui.petCount) {
    const n = pets.length;
    const name = typeof base.name === 'string' ? base.name : '小女仆';
    const pos = base.position && typeof base.position === 'object' ? (base.position as Record<string, unknown>) : {};
    pets.push({
      ...base,
      id: 'pet' + (n + 1),
      name: name + '·' + (n + 1),
      position: { ...pos, corner: corners[n % corners.length] },
    });
  }
  while (pets.length > ui.petCount && pets.length > 1) pets.pop();
  return { ...main, pets };
}
/** 成品聚合：{ main: 合并后的条目 }。顶层整段替换（与原 host 口径一致）。 */
function mergedConfig(): Record<string, Record<string, unknown>> {
  return { main: adaptPetsForScreen({ ...builtinConfig, ...userConfig }) };
}

function reloadUserConfig(): void {
  const raw = native.readUserConfig();
  userConfig = raw.trim() === '' ? {} : parseJsonc(raw, 'main-config.jsonc');
}
/**
 * 把 UI 设置里的透明度贴到渲染容器上。
 * WebView 整页透明，只能靠元素 opacity（宠物/气泡/菜单一起变，不会只透半只）。
 */
function applyUiPrefs(): void {
  const app = document.getElementById('dsh-pet-app');
  // 透明度改由原生窗口 alpha 控制（PetService.applyWindowOpacity）：窗口级合成最可靠，
  // 不依赖 evaluateJavascript 是否回到页面、也不怕 React 重挂容器样式。
  // 这里必须保持 1，否则会和窗口 alpha 叠乘，滑到 30% 实际只剩 9%。
  if (app !== null) app.style.opacity = '1';
}
/** 点击时随机弹一句台词（台词库为空则什么都不做） */
function sayRandom(petId: string): void {
  const list = uiSettings().sayings;
  if (list.length === 0) return;
  const text = list[Math.floor(Math.random() * list.length)];
  writePet(petId, 'say', { text });
}

async function loadConfigs(): Promise<void> {
  try {
    const r = await realFetch('config.jsonc', { cache: 'no-store' });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    builtinConfig = parseJsonc(await r.text(), 'assets/config.jsonc');
  } catch (e) {
    console.error('[dsh-pet] 内置配置加载失败：' + (e instanceof Error ? e.message : String(e)));
    builtinConfig = {};
  }
  reloadUserConfig();
  configLoaded = true;
}

/** 当前生效的宠物列表（供触摸层跟随与定时任务用） */
function currentPets(): Array<Record<string, unknown>> {
  const main = mergedConfig().main;
  const list = Array.isArray(main.pets) ? (main.pets as Array<Record<string, unknown>>) : [];
  return list;
}

/* ------------------------------------------------------------------ state */

let counter = 0;
const sections: Record<string, Leaf> = {};
const pets: Record<string, Record<string, Leaf>> = {};
let waiters: Array<() => void> = [];

function notifyWaiters(): void {
  const list = waiters;
  waiters = [];
  for (const w of list) w();
}

function writeSection(name: string, data: unknown): void {
  sections[name] = { counter: ++counter, data };
  notifyWaiters();
}

function writePet(petId: string, name: string, data: unknown): void {
  const entry = pets[petId] ?? (pets[petId] = {});
  entry[name] = { counter: ++counter, data };
  notifyWaiters();
}

function snapshot(): State {
  return { counter, sections, pets };
}

/** 长轮询：版本没变就挂起，直到任意叶子被写或超时（上限 10s，与契约一致） */
function waitForChange(since: number, timeoutMs: number): Promise<void> {
  if (counter !== since) return Promise.resolve();
  return new Promise<void>((resolve) => {
    let done = false;
    const finish = (): void => {
      if (done) return;
      done = true;
      resolve();
    };
    waiters.push(finish);
    setTimeout(finish, Math.max(0, Math.min(timeoutMs, 10000)));
  });
}

/* -------------------------------------------------------------------- LLM */

let llmSeq = 0;
const llmPending = new Map<string, (json: string) => void>();

declare global {
  interface Window {
    __dshLlmResult?: (id: string, json: string) => void;
  }
}

window.__dshLlmResult = (id: string, json: string): void => {
  const cb = llmPending.get(id);
  if (cb) {
    llmPending.delete(id);
    cb(json);
  }
};

type LlmReply = { ok: true; text: string } | { ok: false; reason: string; message?: string };

/** 调模型（经 Kotlin OkHttp）：失败返回结构化原因，绝不伪造文本 */
function callLlm(payload: Record<string, unknown>): Promise<LlmReply> {
  return new Promise<LlmReply>((resolve) => {
    const id = 'llm-' + ++llmSeq;
    llmPending.set(id, (json: string) => {
      try {
        resolve(JSON.parse(json) as LlmReply);
      } catch {
        resolve({ ok: false, reason: 'fetch-error', message: '模型响应不可解析' });
      }
    });
    const reqId = native.llm(JSON.stringify(payload));
    // Kotlin 返回真实请求号时以它为准（异步回调的键）
    if (reqId && reqId !== id) {
      const cb = llmPending.get(id);
      if (cb) {
        llmPending.delete(id);
        llmPending.set(reqId, cb);
      }
    }
    setTimeout(() => {
      if (llmPending.has(id) || llmPending.has(reqId)) {
        llmPending.delete(id);
        llmPending.delete(reqId);
        resolve({ ok: false, reason: 'fetch-error', message: '模型调用超时' });
      }
    }, 120000);
  });
}

function aiSettings(): { enabled: boolean; provider: string; baseUrl: string; model: string; maxTokens: number } {
  let raw: Record<string, unknown> = {};
  try {
    raw = JSON.parse(native.readAiSettings() || '{}') as Record<string, unknown>;
  } catch {
    raw = {};
  }
  return {
    // 开关是「关闭 AI」用的：只要接口地址与模型名都填了就当作启用（用户忘了打开开关不应当变成「点了没反应」）
    enabled:
      raw.enabled === true ||
      (typeof raw.baseUrl === 'string' &&
        raw.baseUrl.trim() !== '' &&
        typeof raw.model === 'string' &&
        raw.model.trim() !== ''),
    provider: typeof raw.provider === 'string' ? raw.provider : 'openai-compatible',
    baseUrl: typeof raw.baseUrl === 'string' ? raw.baseUrl : '',
    model: typeof raw.model === 'string' ? raw.model : '',
    maxTokens: typeof raw.maxTokens === 'number' ? raw.maxTokens : 1024,
  };
}

/* ---------------------------------------------------------------- 时间背景 */

/** 精简版时间背景（原 host/time-context.ts 的等价语义：日期/星期/时段/工作日或周末） */
function timeContext(): string {
  const d = new Date();
  const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][d.getDay()];
  const h = d.getHours();
  const period = h < 6 ? '凌晨' : h < 11 ? '上午' : h < 13 ? '中午' : h < 18 ? '下午' : h < 23 ? '晚上' : '深夜';
  const weekend = d.getDay() === 0 || d.getDay() === 6;
  const pad = (n: number): string => String(n).padStart(2, '0');
  return `当前时间：${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日 ${week} ${period} ${pad(h)}:${pad(d.getMinutes())}（${weekend ? '周末' : '工作日'}）`;
}

/* ------------------------------------------------------------------ 记忆 */

type Msg = { role: 'user' | 'assistant'; content: string };
type Memory = Record<string, Msg[]>;

function loadMemory(): Memory {
  try {
    const raw = native.readMemory();
    if (!raw.trim()) return {};
    const v = JSON.parse(raw) as unknown;
    return v && typeof v === 'object' ? (v as Memory) : {};
  } catch {
    return {};
  }
}

function saveMemory(mem: Memory): void {
  native.writeMemory(JSON.stringify(mem));
}

/* ---------------------------------------------------------------- 碎碎念 */

function petEntry(petId: string): Record<string, unknown> {
  const main = mergedConfig().main;
  const list = currentPets();
  const hit = list.find((p) => p.id === petId);
  return { ...main, ...(hit ?? {}) };
}

function personaOf(petId: string): string {
  const entry = petEntry(petId);
  const base = typeof entry.whisperPrompt === 'string' ? entry.whisperPrompt : '';
  const name = typeof entry.name === 'string' ? entry.name : petId;
  return base + '\n你的名字是“' + name + '”。\n' + timeContext();
}

const memesOf = (): Record<string, string> => {
  const m = mergedConfig().main.memes;
  return m && typeof m === 'object' ? (m as Record<string, string>) : {};
};

function pickMeme(): string | undefined {
  const keys = Object.keys(memesOf());
  if (keys.length === 0) return undefined;
  return keys[Math.floor(Math.random() * keys.length)];
}

async function generateWhisper(petId: string): Promise<LlmReply & { image?: string }> {
  const cfg = petEntry(petId);
  const ai = aiSettings();
  if (!ai.enabled) return { ok: false, reason: 'provider-missing', message: '未启用 AI（设置页填写接口与密钥）' };
  const imageOn = cfg.whisperImageEnabled !== false;
  const image = imageOn ? pickMeme() : undefined;
  const memeDesc = image ? memesOf()[image] : undefined;
  const userMsg =
    (image && memeDesc ? '（这次会配一张表情包：' + memeDesc + '，请配合这张图说一句）\n' : '') +
    '现在随口碎碎念一句。';
  const r = await callLlm({
    kind: 'whisper',
    system: personaOf(petId),
    messages: [{ role: 'user', content: userMsg }],
    maxTokens: ai.maxTokens,
  });
  if (!r.ok) return r;
  return { ok: true, text: r.text, image };
}

async function generateChat(petId: string, text: string): Promise<LlmReply & { image?: string }> {
  const ai = aiSettings();
  if (!ai.enabled) return { ok: false, reason: 'provider-missing', message: '未启用 AI（设置页填写接口与密钥）' };
  const cfg = petEntry(petId);
  const rounds = typeof cfg.chatMemoryRounds === 'number' ? cfg.chatMemoryRounds : 5;
  const mem = loadMemory();
  const hist = mem[petId] ?? [];
  const context = rounds > 0 ? hist.slice(-rounds * 2) : [];
  const imageOn = cfg.chatImageEnabled !== false;
  const limit = typeof cfg.chatImageLimit === 'number' ? cfg.chatImageLimit : 10;
  const all = Object.entries(memesOf());
  const pick = limit === 0 ? all : all.slice(0, limit);
  const memeList = imageOn && pick.length > 0 ? pick.map(([k, v]) => k + '：' + v).join('\n') : '';
  const system =
    personaOf(petId) +
    (memeList
      ? '\n如果需要配一张表情包，请在回复末尾单独加一行 [[IMG:图片名]]，图片名只能从下面清单里选一个，不需要配图就不要写这一行：\n' +
        memeList
      : '');
  const r = await callLlm({
    kind: 'chat',
    system,
    messages: [...context, { role: 'user', content: text }],
    maxTokens: ai.maxTokens,
  });
  if (!r.ok) return r;
  let reply = r.text;
  let image: string | undefined;
  const m = /\[\[IMG:([^\]]+)\]\]/.exec(reply);
  if (m && memesOf()[m[1].trim()] !== undefined) {
    image = m[1].trim();
    reply = reply.replace(m[0], '').trim();
  }
  mem[petId] = [...hist, { role: 'user', content: text }, { role: 'assistant', content: reply }];
  saveMemory(mem);
  return { ok: true, text: reply, image };
}

/* ------------------------------------------------------------- 定时碎碎念 */

let whisperTimer: ReturnType<typeof setInterval> | null = null;

function startWhisperLoop(): void {
  if (whisperTimer !== null) clearInterval(whisperTimer);
  whisperTimer = setInterval(() => {
    void (async () => {
      const ai = aiSettings();
      if (!ai.enabled) return;
      const sec = ((): number => {
        const ev = mergedConfig().main.eventsRefreshSec as Record<string, number> | undefined;
        const v = ev?.whisper;
        return typeof v === 'number' && v > 0 ? v : 300;
      })();
      // 用 1s 心跳累计，避免配置改后要重启
      elapsed += 1;
      if (elapsed < sec) return;
      elapsed = 0;
      for (const p of currentPets()) {
        const id = typeof p.id === 'string' ? p.id : '';
        if (!id || p.whisperEnabled !== true) continue;
        const r = await generateWhisper(id);
        if (r.ok) writePet(id, 'say', r.image ? { text: r.text, image: r.image } : { text: r.text });
        else console.warn('[dsh-pet] 碎碎念失败 reason=' + r.reason + (r.message ? ' ' + r.message : ''));
      }
    })();
  }, 1000);
}

let elapsed = 0;

/* ------------------------------------------------------------ 触摸层跟随 */

/**
 * 触摸层命中选择器：整页只有这三类元素真的吃指针（其余全是 pointer-events:none）。
 *
 * 为什么不再按「宠物实况 + size×9/16」推算：命中区（HIT_BOX）只占舞台的 37.5%×79%，
 * 按舞台算会多出一大圈死区——圈内的点击被触摸层吃掉、下层应用收不到，表现就是
 * 「点哪都没反应」；触摸层窗口比命中区大多少，宠物旁边就废掉多少。
 * 直接读 DOM 实测包围盒，命中区与窗口 1:1。
 */
const TOUCH_HIT_SELECTORS = [
  '.dsh-pet-hit',
  '.dsh-pet-menu-column',
  '.dsh-pet-chat',
  '.dsh-pet-bubble.is-clickable',
];
/** 上报节流（ms）：60fps 每帧一次 updateViewLayout 会把 WindowManager 压垮，整机点按发飘 */
const TOUCH_SYNC_MS = 60;
/** 心跳间隔（ms）：Kotlin 看门狗 6 秒收不到上报就会把触摸层收回 1×1 */
const TOUCH_HEARTBEAT_MS = 2000;
/** 命中块上限：与 Kotlin 侧 PetService.MAX_TOUCH_LAYERS 保持一致 */
const TOUCH_MAX_LAYERS = 8;
/** 上一次上报的归一化矩形列表（去重：内容没变就不过桥） */
let lastTouchKey = '';
/** 上一次真正过桥的时刻（心跳用：矩形没变也至少每 2 秒上报一次） */
let lastPushAt = 0;
let lastTouchAt = 0;

/**
 * 把命中元素的包围盒上报给 Kotlin 移动触摸层窗口。
 *
 * **坐标口径 = 视口归一化比例（0..1）**，不是 px：JS 侧只有 CSS px（受 devicePixelRatio /
 * viewport 缩放影响），Kotlin 侧只有设备 px，两边各自换算必然漂移（此前正是这里错位，
 * 窗口落在宠物旁边，于是点不中）。归一化后由 Kotlin 乘真实屏幕尺寸，永远对齐。
 */
function syncTouchLayer(): void {
  const now = Date.now();
  if (now - lastTouchAt < TOUCH_SYNC_MS) return;
  lastTouchAt = now;
  const vw = window.innerWidth;
  const vh = window.innerHeight;
  if (!(vw > 0) || !(vh > 0)) return;
  // 逐个元素实测包围盒，**只统计真正可见可点的**：
  //  · display:none / 已脱离布局 → getClientRects() 为空
  //  · visibility:hidden / pointer-events:none → 根本不收指针
  //  · opacity≈0（淡出但还没卸载的菜单/气泡）→ 屏幕上根本没东西
  // 曾经只判断 width/height > 0，结果「淡出后仍留在 DOM 里」的菜单列把并集撑到近半屏，
  // 那圈空白全变成点不动的死区（用户反馈的「桌面无法操作」）。
  // 逐元素收集**独立矩形**（不再求并集）。每个矩形在宿主对应一个独立触摸窗口，
  // 元素之间的空白永远不会落进任何窗口——这是「退到桌面点不动其它 App」的
  // 结构性修复：旧版把多元素并集当一块上报，并集会横跨整屏。
  const rects: { x: number; y: number; w: number; h: number }[] = [];
  for (const sel of TOUCH_HIT_SELECTORS) {
    const nodes = document.querySelectorAll(sel);
    for (let i = 0; i < nodes.length; i++) {
      const el = nodes[i];
      if (!isReallyInteractive(el)) continue;
      const r = el.getBoundingClientRect();
      // **必须与视口求交**。有些面板关闭时不是 display:none，而是被 transform 挪到屏幕外
      // （left = -9999 之类）：width 依然 > 0，直接上报就会得到一条横跨全屏的窄带死区。
      const l = Math.max(0, r.left);
      const t = Math.max(0, r.top);
      const rr = Math.min(vw, r.right);
      const bb = Math.min(vh, r.bottom);
      const cw = rr - l;
      const ch = bb - t;
      if (!(cw > 0) || !(ch > 0)) continue;
      // 单块保险丝：单元素超过视口 90% 说明量测异常（隐藏面板未过滤 / transform 拉满），
      // 宁可这一块点不到，也不能让它吃掉桌面。
      if ((cw / vw) * (ch / vh) > 0.9) {
        console.warn('[dsh-pet] 命中块异常过大，跳过 ' + sel + ' ' + cw + 'x' + ch);
        continue;
      }
      rects.push({ x: l, y: t, w: cw, h: ch });
    }
  }
  // 选择器顺序保证宠物本体排在前面，截断时优先保留宠物
  if (rects.length > TOUCH_MAX_LAYERS) rects.length = TOUCH_MAX_LAYERS;
  pushTouchRects(rects);
}

/** 元素此刻是否真的能接收指针（隐藏/透明/不吃事件的一律排除） */
function isReallyInteractive(el: Element): boolean {
  if (el.getClientRects().length === 0) return false;
  const cs = window.getComputedStyle(el);
  if (cs.display === 'none' || cs.visibility === 'hidden') return false;
  if (cs.pointerEvents === 'none') return false;
  const op = parseFloat(cs.opacity);
  if (Number.isFinite(op) && op < 0.05) return false;
  return true;
}
/** 去重后过桥（归一化，4 位小数 ≈ 0.1px 级）：数组形式，一块一个触摸窗口 */
function pushTouchRects(rects: { x: number; y: number; w: number; h: number }[]): void {
  const now = Date.now();
  const vw = window.innerWidth;
  const vh = window.innerHeight;
  if (!(vw > 0) || !(vh > 0)) return;
  const r4 = (v: number): number => Math.round(v * 10000) / 10000;
  const norm = rects.map((r) => ({
    x: r4(r.x / vw),
    y: r4(r.y / vh),
    w: r4(r.w / vw),
    h: r4(r.h / vh),
  }));
  const key = JSON.stringify(norm);
  // 心跳：矩形没变也每 2 秒强制过一次桥。Kotlin 侧有「6 秒没收到上报就把触摸层收回 1×1」
  // 的看门狗（防退后台后 rAF 冻结、窗口停在旧位置锁死桌面），没有心跳会被误判成停摆。
  if (key === lastTouchKey && now - lastPushAt < TOUCH_HEARTBEAT_MS) return;
  lastPushAt = now;
  lastTouchKey = key;
  const api = window.DshNative;
  if (api && typeof api.applyTouchLayersJson === 'function') {
    try {
      api.applyTouchLayersJson(key);
      return;
    } catch (e) {
      console.warn('[dsh-pet] applyTouchLayersJson 失败，退化为单块上报: ' + String(e));
    }
  }
  // 兼容旧宿主（v1.0.5 及以前只有单块接口）：退化为并集上报
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (const r of norm) {
    minX = Math.min(minX, r.x);
    minY = Math.min(minY, r.y);
    maxX = Math.max(maxX, r.x + r.w);
    maxY = Math.max(maxY, r.y + r.h);
  }
  if (!Number.isFinite(minX)) {
    native.syncTouchLayer(0, 0, 0, 0);
    return;
  }
  native.syncTouchLayer(minX, minY, maxX - minX, maxY - minY);
}
function startPoseLoop(): void {
  const tick = (): void => {
    syncTouchLayer();
    requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
  // rAF 在应用退到后台后会被系统冻结，命中区就再也不上报了。补一条定时器：
  // 冻结期间仍能（以较慢节奏）继续上报，两者都走 TOUCH_SYNC_MS 节流，不会加倍开销。
  window.setInterval(syncTouchLayer, 500);
}

/* ------------------------------------------------------------ 输入焦点 */
/**
 * 渲染窗口平时是 FLAG_NOT_FOCUSABLE（不抢桌面焦点，否则会顶掉桌面/其它 App 的
 * 输入法状态），代价是 WebView 里的 <input> 点了也拿不到焦点、软键盘永远不弹。
 *
 * 这里把「现在有输入框聚焦」这件事告诉原生：原生临时切到可聚焦 + requestFocus +
 * showSoftInput；失焦后恢复 NOT_FOCUSABLE。桌宠要能聊天，这一环必须有。
 */
window.addEventListener('focusin', (e) => {
  const t = e.target as HTMLElement | null;
  if (t === null) return;
  if (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable) {
    native.setFocusMode(true);
  }
});
window.addEventListener('focusout', () => {
  // 失焦后可能立刻聚焦到另一个输入框，延一拍再判断当前活动元素
  window.setTimeout(() => {
    const a = document.activeElement as HTMLElement | null;
    if (a === null) {
      native.setFocusMode(false);
      return;
    }
    if (a.tagName !== 'INPUT' && a.tagName !== 'TEXTAREA' && !a.isContentEditable) {
      native.setFocusMode(false);
    }
  }, 80);
});

/* --------------------------------------------------------------- 端点实现 */

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
  });

function q(url: URL, key: string): string {
  return url.searchParams.get(key) ?? '';
}

function readBody(init?: RequestInit): Record<string, unknown> {
  const b = init?.body;
  if (typeof b !== 'string' || b === '') return {};
  try {
    const v = JSON.parse(b) as unknown;
    return v && typeof v === 'object' ? (v as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

async function handleApi(url: URL, init?: RequestInit): Promise<Response> {
  const p = url.pathname.slice(API.length);
  const method = (init?.method ?? 'GET').toUpperCase();

  // ---- 读状态 ----
  if (p === '/state' && method === 'GET') {
    const since = q(url, 'since');
    const timeout = q(url, 'timeout');
    if (since !== '' && timeout !== '') {
      const sinceNum = Number(since);
      const timeoutNum = Number(timeout);
      if (Number.isFinite(sinceNum) && Number.isFinite(timeoutNum)) {
        await waitForChange(sinceNum, timeoutNum);
      }
    }
    return json(snapshot());
  }
  if (p === '/config' && method === 'GET') {
    if (!configLoaded) await loadConfigs();
    return json(mergedConfig());
  }
  if (p === '/config/meta' && method === 'GET') {
    return json({
      configPath: 'main-config.jsonc（应用私有目录）',
      dataDir: '应用私有目录 /dsh-pet',
      profile: 'android',
    });
  }
  if (p === '/models' && method === 'GET') {
    const ai = aiSettings();
    return json({
      providers: [
        {
          provider: ai.provider,
          models: ai.model ? [{ id: ai.model, name: ai.model }] : [],
        },
      ],
    });
  }
  if (p === '/chat' && method === 'GET') {
    const petId = q(url, 'pet') || 'main';
    const mem = loadMemory();
    return json({ pet: petId, messages: mem[petId] ?? [] });
  }
  if (p === '/pose' && method === 'GET') {
    const petId = q(url, 'pet') || 'main';
    const pose = readPose(petId);
    return pose ? json({ ok: true, ...pose }) : json({ ok: false, reason: 'not-visible' });
  }

  // ---- 说话 ----
  if (p === '/whisper' && method === 'POST') {
    const petId = q(url, 'pet') || 'main';
    // 立即回执：先把「在想了」打在气泡上，不要让用户面对一个没反应的宠物猜半天
    writePet(petId, 'say', { text: '……' });
    const r = await generateWhisper(petId);
    if (!r.ok) {
      // 失败也必须可见（原项目只写 console.warn，手机上看不到日志）
      const why = r.reason === 'provider-missing' ? '未填接口或密钥' : r.message || r.reason;
      writePet(petId, 'say', { text: '（碎碎念失败：' + why + '）' });
      return json({ ok: false, reason: r.reason, message: r.message }, 200);
    }
    writePet(petId, 'say', r.image ? { text: r.text, image: r.image } : { text: r.text });
    return json({ ok: true, text: r.text, ...(r.image ? { image: r.image } : {}) });
  }
  if (p === '/chat' && method === 'POST') {
    const petId = q(url, 'pet') || 'main';
    const text = typeof readBody(init).text === 'string' ? String(readBody(init).text) : '';
    if (!text) return json({ ok: false, reason: 'bad-request', message: '缺少 text' }, 400);
    const r = await generateChat(petId, text);
    if (!r.ok) {
      const why = r.reason === 'provider-missing' ? '未填接口或密钥' : r.message || r.reason;
      writePet(petId, 'say', { text: '（回复失败：' + why + '）' });
      return json({ ok: false, reason: r.reason, message: r.message }, 200);
    }
    writePet(petId, 'say', r.image ? { text: r.text, image: r.image } : { text: r.text });
    return json({ ok: true, text: r.text, ...(r.image ? { image: r.image } : {}) });
  }
  if (p === '/broadcast' && method === 'POST') {
    const petId = q(url, 'pet') || 'main';
    const body = readBody(init);
    const text = typeof body.text === 'string' ? body.text : '';
    if (!text) return json({ ok: false, reason: 'bad-request' }, 400);
    const data: Record<string, unknown> = { text };
    if (typeof body.image === 'string') data.image = body.image;
    if (typeof body.url === 'string') data.url = body.url;
    if (typeof body.duration === 'number') data.duration = body.duration;
    if (typeof body.anim === 'boolean') data.anim = body.anim;
    writePet(petId, 'say', data);
    return json({ ok: true });
  }
  if (p === '/bubble/clear' && method === 'POST') {
    const petId = q(url, 'pet') || 'main';
    writePet(petId, 'say', { clear: true });
    return json({ ok: true });
  }
  if (p === '/balance' && method === 'POST') {
    // Android 端没有 DSH 的凭据体系：按 standalone 的同一口径**显式降级**，不伪造数字
    writeSection('balance', {
      provider: 'dsh',
      ok: false,
      reason: 'unsupported',
      message: '安卓端未接入 DSH 凭据，余额不可用',
      manual: true,
    });
    return json({ ok: true });
  }

  // ---- 动作 ----
  if (p === '/anim' && method === 'POST') {
    const petId = q(url, 'pet') || 'main';
    const body = readBody(init);
    const data: Record<string, unknown> = {};
    if (typeof body.name === 'string' && body.name) data.name = body.name;
    if (typeof body.url === 'string' && body.url) data.url = body.url;
    if (data.name === undefined && data.url === undefined) return json({ ok: false, reason: 'bad-request' }, 400);
    if (body.facing === 'left' || body.facing === 'right') data.facing = body.facing;
    const playbackId = 'pb-' + ++counter;
    data.playbackId = playbackId;
    writePet(petId, 'anim', data);
    return json({ ok: true, playbackId });
  }
  if (p.startsWith('/playback/') && p.endsWith('/report') && method === 'POST') {
    return json({ ok: true });
  }
  if (p === '/pose/report' && method === 'POST') {
    return json({ ok: true });
  }
  if (p === '/position' && method === 'POST') {
    const petId = q(url, 'pet') || 'main';
    const body = readBody(init);
    const x = Number(body.x);
    const y = Number(body.y);
    if (!Number.isFinite(x) || !Number.isFinite(y)) return json({ ok: false, reason: 'bad-request' }, 400);
    const mode = body.mode === 'instant' || body.mode === 'spring' || body.mode === 'line' ? body.mode : 'spring';
    const cx = Math.min(1, Math.max(0, x));
    const cy = Math.min(1, Math.max(0, y));
    const data: Record<string, unknown> = { x: cx, y: cy, mode };
    if (mode === 'line') data.duration = typeof body.duration === 'number' ? body.duration : 600;
    writePet(petId, 'position', data);
    return json({ ok: true, x: cx, y: cy });
  }
  if (p === '/throw' && method === 'POST') {
    const petId = q(url, 'pet') || 'main';
    const body = readBody(init);
    const angle = Number(body.angle ?? 0);
    const power = Number(body.power ?? 1);
    if (!Number.isFinite(angle) || !Number.isFinite(power) || power <= 0) {
      return json({ ok: false, reason: 'bad-request' }, 400);
    }
    writePet(petId, 'throw', { angle, power });
    return json({ ok: true });
  }
  if (p === '/lock' && method === 'POST') {
    const petId = q(url, 'pet') || 'main';
    const lockId = 'lk-' + ++counter;
    writePet(petId, 'lock', { locked: true, expiresAt: Date.now() + 30000 });
    return json({ ok: true, lockId, expiresAt: Date.now() + 30000 });
  }
  if (p === '/lock/release' && method === 'POST') {
    const petId = q(url, 'pet') || 'main';
    writePet(petId, 'lock', { locked: false });
    return json({ ok: true });
  }

  // ---- 配置 ----
  if (p === '/pet-display' && method === 'POST') {
    const body = readBody(init);
    const id = typeof body.id === 'string' ? body.id : '';
    const display = body.display;
    if (!id || (display !== 'web' && display !== 'desktop' && display !== 'both' && display !== 'none')) {
      return json({ ok: false, reason: 'bad-request' }, 400);
    }
    const next = { ...userConfig };
    const petsArr = Array.isArray(next.pets) ? (next.pets as Array<Record<string, unknown>>) : [];
    const list = petsArr.length > 0 ? petsArr : currentPets().map((x) => ({ ...x }));
    const hit = list.find((x) => x.id === id);
    if (!hit) return json({ ok: false, reason: 'not-found' }, 404);
    hit.display = display;
    next.pets = list;
    userConfig = next;
    native.writeUserConfig(JSON.stringify(next, null, 2));
    writeSection('config', {});
    return json({ ok: true });
  }
  if (p === '/config' && method === 'PUT') {
    const body = readBody(init);
    userConfig = { ...userConfig, ...body };
    native.writeUserConfig(JSON.stringify(userConfig, null, 2));
    writeSection('config', {});
    return json(mergedConfig());
  }
  if (p === '/config' && method === 'POST') {
    userConfig = {};
    native.writeUserConfig('');
    writeSection('config', {});
    return json(mergedConfig());
  }
  if (p === '/reload') {
    reloadUserConfig();
    writeSection('config', {});
    return json({ ok: true });
  }

  // ---- 菜单 ----
  if (p === '/menu/item' && method === 'POST') {
    return json({ ok: true });
  }
  if (p === '/menu/item' && method === 'DELETE') {
    return json({ ok: true, removed: 0 });
  }
  if (p === '/menu/events') {
    return json({});
  }
  if (p === '/menu/click' && method === 'POST') {
    return json({ ok: true });
  }

  // ---- 工作状态 ----
  if (p === '/work-status' && method === 'POST') {
    const body = readBody(init);
    writeSection('workStatus', { state: body.state ?? null, task: body.task ?? null });
    return json({ ok: true });
  }
  if (p === '/work-status' && method === 'DELETE') {
    writeSection('workStatus', { state: null, task: null });
    return new Response(null, { status: 204 });
  }

  // ---- 素材：转发到本地目录（video 走 blob，必须经 fetch） ----
  if (p.startsWith('/thumb/')) {
    const rest = p.slice('/thumb/'.length);
    const slash = rest.indexOf('/');
    const file = decodeURIComponent(slash >= 0 ? rest.slice(slash + 1) : rest);
    return realFetch('webm/' + encodeURIComponent(file), { cache: 'default' });
  }

  return json({ ok: false, reason: 'not-found' }, 404);
}

/* ------------------------------------------------------------- fetch 拦截 */

const realFetch = window.fetch.bind(window);

function installFetch(): void {
  window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const raw = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
    let url: URL;
    try {
      url = new URL(raw, location.href);
    } catch {
      return realFetch(input as RequestInfo, init);
    }
    if (!url.pathname.startsWith(API + '/')) return realFetch(input as RequestInfo, init);
    try {
      return await handleApi(url, init);
    } catch (e) {
      console.error('[dsh-pet] 宿主路由失败：' + (e instanceof Error ? e.message : String(e)));
      return json({ ok: false, reason: 'internal' }, 500);
    }
  };
}

/**
 * 指针探针（诊断用，只记前 12 次）：确认触摸是否真的进到 WebView、落在哪个元素上、
 * 以及 JS 侧的视口口径（innerWidth / dpr）。日志走 logcat（tag=dsh-pet），
 * 用于核对「触摸层窗口位置」与「DOM 命中区」是否重合。
 */
function installPointerProbe(): void {
  let n = 0;
  const one = (kind: string) => (e: Event): void => {
    if (n >= 12) return;
    n++;
    const t = e.target as HTMLElement | null;
    const cls =
      t && typeof t.className === 'string' && t.className !== '' ? t.className : String(t?.nodeName ?? '?');
    const m = e as MouseEvent;
    native.log(
      '[probe] ' +
        kind +
        ' target=' +
        cls +
        ' xy=' +
        Math.round(m.clientX ?? -1) +
        ',' +
        Math.round(m.clientY ?? -1) +
        ' vw=' +
        window.innerWidth +
        ' vh=' +
        window.innerHeight +
        ' dpr=' +
        window.devicePixelRatio,
    );
  };
  document.addEventListener('pointerdown', one('pointerdown'), true);
  document.addEventListener('click', one('click'), true);
}
/* ------------------------------------------------------------------ 启动 */
export async function startHost(): Promise<void> {
  installFetch();
  await loadConfigs();
  // 首拉基线由渲染层负责；这里只保证配置与定时任务就绪
  writeSection('notify', {});
  startWhisperLoop();
  startPoseLoop();
  installPointerProbe();
  applyUiPrefs();
  // 设置页改动后回到页面时重读用户配置
  window.addEventListener('focus', () => {
    reloadUserConfig();
    applyUiPrefs();
  });
  native.log('host started, pets=' + currentPets().length);
}

/** 供原生设置页保存后主动刷新（Kotlin 调 evaluateJavascript） */
declare global {
  interface Window {
    __dshReloadConfig?: () => void;
    /** 点击台词（原生侧点击回执里调） */
    __dshSaying?: (petId: string) => void;
  }
}
window.__dshReloadConfig = (): void => {
  reloadUserConfig();
  applyUiPrefs();
  writeSection('config', {});
};
/** 点击台词：从台词库里随机弹一句（原生侧在点击回执里调） */
window.__dshSaying = (petId: string): void => sayRandom(petId === '' ? 'main' : petId);
