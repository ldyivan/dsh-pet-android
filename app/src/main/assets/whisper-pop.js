/**
 * 说话气泡「点一下戳破」补丁（Android 独立入口，不改 dsh-pet.js）。
 *
 * 关键机制：WebView 自身 FLAG_NOT_TOUCHABLE，真实点击由 Kotlin 原生触摸层接收后
 * 以 dispatchTouchEvent 注入 WebView。气泡默认 pointer-events:none 且不在命中区，
 * 所以要用「专用触摸窗」盖住气泡才点得到。
 *
 * 本脚本（轮询式，不用 MutationObserver：React 重建节点时 observer 的 target
 * 往往是父容器，容易漏掉新气泡）：
 *  1) 每 200ms 查一次当前可见的说话气泡；
 *  2) 气泡包围盒变化时 → DshNative.whisperBubbleRect({x,y,w,h}) 铺开触摸窗；
 *  3) 气泡消失 → 报 {active:false} 收回；
 *  4) Kotlin 点击时调 window.__dshPopWhisper() → 加 .is-popping 播 420ms 戳破动画
 *     → 动画结束把气泡隐起来（只收气泡，不碰动画/其它气泡）。
 */
(function () {
  'use strict';
  var POP_MS = 420;
  var SEL = '.dsh-pet-bubble.dsh-pet-whisper';
  var STYLE_TAG = 'dsh-whisper-pop-style';
  var TICK_MS = 200;

  var popping = false;
  var popTimer = 0;
  var lastRect = '';
  var lastActive = false;

  function injectCss() {
    if (document.querySelector('style[data-plugin-css="' + STYLE_TAG + '"]') !== null) return;
    var tag = document.createElement('style');
    tag.dataset.plugin = 'whisper-pop';
    tag.dataset.pluginCss = STYLE_TAG;
    tag.textContent = [
      // 戳破：鼓一下 → 缩成小泡泡 → 模糊散掉。transform 必带 translateX(-50%)
      // 否则动画一覆盖就丢掉居中，气泡会横向跳半个身位。
      // 破裂：先猛吸一口气鼓胀、轻微抖动，再「啪」地过冲后急速碎散。
      // transform-origin 放在底部尾巴处，胀缩从气泡根部发生，更像被戳破。
      SEL + '.is-popping{pointer-events:none;transform-origin:50% 100%;' +
        'animation:dsh-whisper-pop-out ' + POP_MS + 'ms cubic-bezier(.16,.84,.28,1) forwards}',
      '@keyframes dsh-whisper-pop-out{' +
        '0%{transform:translateX(-50%) scale(1,1) rotate(0);opacity:1;filter:blur(0)}' +
        '8%{transform:translateX(-51%) scale(1.02,1.04) rotate(-1.2deg);opacity:1;filter:blur(0)}' +
        '15%{transform:translateX(-49%) scale(1.05,1.03) rotate(1.2deg);opacity:1;filter:blur(0)}' +
        '22%{transform:translateX(-50%) scale(1.18,1.22) rotate(0);opacity:.98;filter:blur(0)}' +
        '30%{transform:translateX(-50%) scale(1.26,1.3);opacity:.9;filter:blur(.4px)}' +
        '46%{transform:translateX(-50%) scale(.82,.86);opacity:.6;filter:blur(1.5px)}' +
        '66%{transform:translateX(-50%) scale(1.06,1.02);opacity:.36;filter:blur(3px)}' +
        '100%{transform:translateX(-50%) scale(.4,.34);opacity:0;filter:blur(9px)}}',
    ].join('');
    document.head.appendChild(tag);
  }

  /** 当前可见的说话气泡：取 .is-on 且宽高>8 的最后一个（最新的那层）。 */
  function findWhisper() {
    var nodes = document.querySelectorAll(SEL);
    for (var i = nodes.length - 1; i >= 0; i--) {
      var el = nodes[i];
      if (el.classList.contains('is-popping')) continue;
      if (!el.classList.contains('is-on')) continue;
      // 已被戳破（内联 visibility:hidden）的不算可见，避免重复触发
      if (el.getAttribute('data-dsh-popped') === '1') continue;
      if (el.style.visibility === 'hidden') continue;
      var r = el.getBoundingClientRect();
      if (r.width > 8 && r.height > 8) return el;
    }
    return null;
  }

  function nat() {
    var n = window.DshNative;
    return (n && typeof n.whisperBubbleRect === 'function') ? n : null;
  }

  function report() {
    if (popping) return;
    var n = nat();
    if (n === null) return;
    var el = findWhisper();
    if (el === null) {
      if (lastActive) {
        lastActive = false;
        lastRect = '';
        try { n.whisperBubbleRect(JSON.stringify({ active: false })); } catch (e) {}
      }
      return;
    }
    var r = el.getBoundingClientRect();
    // 外扩 3px 兜住半透明描边/阴影边（点边也要能戳）
    var json = JSON.stringify({
      x: (r.left - 3) / window.innerWidth,
      y: (r.top - 3) / window.innerHeight,
      w: (r.width + 6) / window.innerWidth,
      h: (r.height + 6) / window.innerHeight,
    });
    if (json !== lastRect) {
      lastRect = json;
      lastActive = true;
      try { n.whisperBubbleRect(json); } catch (e) {}
    }
  }

  /**
   * 视觉隐藏所有说话气泡（戳破动画结束后调）。
   *
   * 关键：**绝不改 class**（is-on/is-popping 都由 React 的虚拟 DOM 管）。
   * 之前移除 is-on 后，React 仍认为 is-on 存在 → 下次渲染 diff 判定「无变化」→
   * 不再补回 is-on → 气泡永久不显示，必须靠别的功能刷新一次才恢复（真机反馈的 bug）。
   * 这里只写内联 style（React 不追踪 style 的这部分），且给气泡打上 data 标记，
   * 后续 React 换新台词/新节点时标记随节点消失，隐藏自然失效。
   */
  function conceal() {
    var nodes = document.querySelectorAll(SEL);
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      n.setAttribute('data-dsh-popped', '1');
      // 记住戳破时的文本：revive 靠它判断「是不是换新台词了」
      n.setAttribute('data-dsh-popped-text', (n.textContent || '').slice(0, 200));
      n.style.visibility = 'hidden';
      n.style.opacity = '0';
      n.style.pointerEvents = 'none';
    }
    lastActive = false;
    lastRect = '';
    var nn = nat();
    if (nn !== null) { try { nn.whisperBubbleRect(JSON.stringify({ active: false })); } catch (e) {} }
  }

  /** Kotlin 点击入口：播戳破动画，结束后收起。 */
  window.__dshPopWhisper = function () {
    injectCss();
    if (popping) return false;
    var el = findWhisper();
    if (el === null) return false;
    popping = true;
    el.classList.add('is-popping');   // 只是临时加，动画结束由下方清掉（React 不追踪此类名，安全）
    // 动画期间立刻收回触摸窗，避免连点
    lastActive = false;
    lastRect = '';
    var n = nat();
    if (n !== null) { try { n.whisperBubbleRect(JSON.stringify({ active: false })); } catch (e) {} }
    popTimer = window.setTimeout(function () {
      popTimer = 0;
      popping = false;
      // 清掉动画类（回到 React 认知的原始类集），再做内联视觉隐藏
      var pops = document.querySelectorAll(SEL + '.is-popping');
      for (var i = 0; i < pops.length; i++) pops[i].classList.remove('is-popping');
      conceal();
    }, POP_MS);
    return true;
  };

  /**
   * 新台词到来时撤销上一次的内联隐藏。
   * 判定依据：节点仍带 data-dsh-popped 且 React 已把它设为 is-on（说明是新台词/重新显示），
   * 则清掉内联样式让 React 的可见状态生效；同时移除标记，避免覆盖新气泡。
   */
  function revive() {
    var nodes = document.querySelectorAll(SEL);
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      if (n.getAttribute('data-dsh-popped') === '1') {
        // 只有内容真的换了（= 新台词），才撤销隐藏；否则会“刚戳破又弹回来”。
        var snap = n.getAttribute('data-dsh-popped-text') || '';
        var now = (n.textContent || '').slice(0, 200);
        if (now === snap) continue;
        n.style.visibility = '';
        n.style.opacity = '';
        n.style.pointerEvents = '';
        n.removeAttribute('data-dsh-popped');
        n.removeAttribute('data-dsh-popped-text');
      }
    }
  }

  setInterval(function () {
    if (!popping) revive();
    report();
  }, TICK_MS);

  window.addEventListener('resize', report);
  window.addEventListener('orientationchange', function () { window.setTimeout(report, 150); });

  injectCss();
  console.log('[whisper-pop] 戳破补丁已挂载（200ms 轮询，__dshPopWhisper）');
})();
