// Android 原生桥（Kotlin @JavascriptInterface）的类型化封装。
// 设计原则（与原项目一致）：平台能力缺失时**显式失败**，不静默伪造数据。
export interface NativeApi {
  /** 读用户层配置 main-config.jsonc（无文件返回空串） */
  readUserConfig(): string;
  /** 写用户层配置（原子写由 Kotlin 侧保证） */
  writeUserConfig(json: string): boolean;
  /** 读对话记忆 memory.json（无文件返回空串） */
  readMemory(): string;
  writeMemory(json: string): boolean;
  /** AI 设置（provider/baseUrl/model/apiKey 等），JSON 字符串 */
  readAiSettings(): string;
  /** 调用模型：payload = {kind:'whisper'|'chat', system, messages, maxTokens}，返回 JSON 结果 */
  llm(payload: string): string;
  /** UI 设置（显示大小/透明度/点击音效/宠物数量/台词库），JSON 字符串 */
  readUiSettings(): string;
  writeUiSettings(json: string): boolean;
  /** 点击音效（开关在 Kotlin 侧读 ui-settings.json 判断） */
  clickSound(): void;
  /** 系统通知 */
  notify(title: string, text: string): void;
  /** 触摸层窗口跟随：命中区包围盒，**视口归一化比例（0..1）**；Kotlin 侧乘真实屏幕尺寸 */
  syncTouchLayer(x: number, y: number, w: number, h: number): void;
  /**
   * 触摸层窗口跟随（多块）：`[{\"x\":..,\"y\":..,\"w\":..,\"h\":..}, ...]`，视口归一化比例。
   * 每个命中元素一块独立悬浮窗——旧版单块并集会横跨整屏，把桌面点成死区。
   */
  applyTouchLayersJson(json: string): void;
  /** 显示 / 暂时隐藏桌宠（走服务端 petHidden，窗口 GONE，恢复瞬时） */
  setPetHidden(hidden: boolean): void;
  /** 日志（adb logcat，tag=dsh-pet） */
  log(msg: string): void;
  /** 轻触觉反馈（长按菜单打开时） */
  haptic(): void;
  /** 打开原生设置页 */
  openSettings(): void;
  /**
   * 输入焦点开关：渲染窗口平时是 FLAG_NOT_FOCUSABLE（不抢桌面焦点），
   * 代价是 WebView 里的 <input> 点了也拿不到焦点、软键盘不弹。
   * 输入框聚焦/失焦时由 host.ts 回调这里。
   */
  setFocusMode(on: boolean): void;
}

declare global {
  interface Window {
    DshNative?: NativeApi;
  }
}

const stub: NativeApi = {
  readUserConfig: () => '',
  writeUserConfig: () => false,
  readMemory: () => '',
  writeMemory: () => false,
  readAiSettings: () => '{}',
  llm: () => JSON.stringify({ ok: false, reason: 'bridge-missing' }),
  readUiSettings: () => '{}',
  writeUiSettings: () => false,
  clickSound: () => {},
  notify: () => {},
  syncTouchLayer: () => {},
  applyTouchLayersJson: () => {},
  setPetHidden: () => {},
  log: (m: string) => console.log('[dsh-pet] ' + m),
  haptic: () => {},
  openSettings: () => {},
  setFocusMode: () => {},
};

export const native: NativeApi = new Proxy({} as NativeApi, {
  get(_t, key: string) {
    const api = window.DshNative ?? stub;
    const fn = (api as unknown as Record<string, unknown>)[key];
    if (typeof fn === 'function') return (fn as (...a: unknown[]) => unknown).bind(api);
    return (stub as unknown as Record<string, unknown>)[key];
  },
});

export const hasNative = (): boolean => typeof window.DshNative !== 'undefined';
