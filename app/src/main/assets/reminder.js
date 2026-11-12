/**
 * 提醒/计划 气泡（Android 独立入口，与 dsh-pet.js 并行加载，不碰其内部闭包）。
 *
 * 触发：Kotlin PetService.fireReminders → evaluateJavascript 调
 *      window.__dshReminderBubble(JSON.stringify({id,title,text,plan}))
 *
 * 职责：
 *  1) 在宠物旁画一条**可点**气泡（DOM 叠层，position:fixed，不依赖 dsh-pet.js 内部）；
 *  2) 气泡命中区通过 DshNative.reminderBubbleRect(json) 上报，Kotlin 侧铺一个专用
 *     触摸层窗口（不复用 dsh-pet.js 的 8 窗口池，避免被其心跳盖掉），点按跳设置页提醒 tab；
 *  3) 停留 N 秒后自动收起（收回点击区）；多条依次排队。
 *
 * 降级：DshNative 不可用 / 无宠物 DOM 时，气泡仍画出（视觉），点击靠系统通知兜底。
 */
(function () {
  'use strict';
  var OWNER = 'reminder';
  var ROOT_ID = 'dsh-reminder-bubble-layer';
  var STYLE_TAG = 'dsh-reminder-style';
  var STAY_MS = 15000;

  var activeId = null;
  var hideTimer = 0;

  function injectCss() {
    if (document.querySelector('style[data-plugin-css="' + STYLE_TAG + '"]') !== null) return;
    var tag = document.createElement('style');
    tag.dataset.plugin = OWNER;
    tag.dataset.pluginCss = STYLE_TAG;
    tag.textContent = [
      '#' + ROOT_ID, '{ position: fixed; inset: 0; pointer-events: none; z-index: 2147483000; }',
      '.dr-bubble',
      '{ pointer-events: none; position: fixed; left: 50%; top: 8%; transform: translateX(-50%);',
      'max-width: 78vw; padding: 12px 18px; border-radius: 16px;',
      'background: rgba(255,255,255,0.96); color: #1F2637;',
      'box-shadow: 0 6px 24px rgba(20,26,43,0.18); border: 1px solid #E4E8F1;',
      'font: 15px/1.4 system-ui, -apple-system, sans-serif; opacity: 0;',
      'transition: opacity .22s ease, transform .22s ease; will-change: opacity; }',
      '.dr-bubble.show { opacity: 1; }',
      '.dr-bubble .dr-title { font-weight: 700; font-size: 15px; color: #141A2B; }',
      '.dr-bubble .dr-title.plan { color: #3B6FD6; }',
      '.dr-bubble .dr-text { font-size: 13px; color: #5B6B8C; margin-top: 3px; }',
      '.dr-bubble .dr-hint { font-size: 11px; color: #9AA3B5; margin-top: 6px; }',
    ].join('');
    document.head.appendChild(tag);
  }

  function ensureLayer() {
    var layer = document.getElementById(ROOT_ID);
    if (layer !== null) return layer;
    layer = document.createElement('div');
    layer.id = ROOT_ID;
    document.body.appendChild(layer);
    var b = document.createElement('div');
    b.className = 'dr-bubble';
    b.setAttribute('data-reminder-bubble', '1');
    layer.appendChild(b);
    return layer;
  }

  function reportRect() {
    var el = document.querySelector('[data-reminder-bubble="1"]');
    if (el === null) return;
    // 视觉气泡不可被 WebView 触摸层点中（渲染窗不可触摸），把命中区报给 Kotlin 铺专用窗口。
    var r = el.getBoundingClientRect();
    if (r.width < 8 || r.height < 8) return;
    var nat = window.DshNative;
    if (nat && typeof nat.reminderBubbleRect === 'function') {
      nat.reminderBubbleRect(JSON.stringify({
        x: r.left / window.innerWidth,
        y: r.top / window.innerHeight,
        w: r.width / window.innerWidth,
        h: r.height / window.innerHeight,
        id: activeId || '',
      }));
    }
  }

  function hideBubble() {
    var el = document.querySelector('[data-reminder-bubble="1"]');
    if (el !== null) el.classList.remove('show');
    activeId = null;
    var nat = window.DshNative;
    if (nat && typeof nat.reminderBubbleRect === 'function') {
      nat.reminderBubbleRect(JSON.stringify({ active: false }));
    }
  }

  /** Kotlin 触发入口。payload 可为 JSON 字符串或对象。 */
  window.__dshReminderBubble = function (payload) {
    var data = (typeof payload === 'string' && payload !== '') ? JSON.parse(payload) : (payload || {});
    var title = String(data.title || '提醒');
    var text = String(data.text || '');
    var plan = !!data.plan;
    activeId = data.id != null ? String(data.id) : '';

    injectCss();
    var layer = ensureLayer();
    var el = layer.querySelector('[data-reminder-bubble="1"]');
    el.innerHTML = '';
    var t = document.createElement('div');
    t.className = 'dr-title' + (plan ? ' plan' : '');
    t.textContent = (plan ? '计划 · ' : '备忘 · ') + title;
    el.appendChild(t);
    if (text !== '') {
      var tx = document.createElement('div');
      tx.className = 'dr-text';
      tx.textContent = text;
      el.appendChild(tx);
    }
    var h = document.createElement('div');
    h.className = 'dr-hint';
    h.textContent = plan ? '点通知 / 进「提醒」逐项勾选' : '点「提醒」查看列表';
    el.appendChild(h);
    // 同步显示（关键：不能只靠 rAF。灭屏/后台 pauseTimers 会冻结 rAF，
    // 若只在 rAF 里加 .show，气泡 opacity 永远停在 0 → 画了但不可见，只剩通知）。
    // 先同步加类让最终态可见（CSS transition 走渲染线程，JS 定时器被冻结也能动）；
    // rAF 仅做补帧/尺寸上报的双保险，冻结时自动跳过不影响可见性。
    el.classList.add('show');
    if (typeof requestAnimationFrame === 'function') {
      requestAnimationFrame(function () { el.classList.add('show'); });
    }
    // 上报点击区：定时兜底 + rAF 双保险（冻结时定时兜底也能报）
    window.setTimeout(reportRect, 40);
    if (typeof requestAnimationFrame === 'function') {
      requestAnimationFrame(function () { requestAnimationFrame(reportRect); });
    }

    if (hideTimer !== 0) window.clearTimeout(hideTimer);
    hideTimer = window.setTimeout(hideBubble, STAY_MS);
  };

  // 窗口尺寸变化（旋转/宠物换位）时重报点击区
  window.addEventListener('resize', function () { if (activeId !== null) reportRect(); });
  window.addEventListener('orientationchange', function () { if (activeId !== null) window.setTimeout(reportRect, 120); });

  console.log('[reminder] 独立气泡入口已挂载（__dshReminderBubble）');
})();