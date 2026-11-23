/**
 * 长按菜单「查询天气」入口（Android 独立入口，不改 dsh-pet.js）。
 *
 * 做法：轮询观察 .dsh-pet-menu 是否出现（长按菜单挂载时），一旦出现且还没加过，
 * 就在第一个菜单列末尾追加一项「查询天气」。点它 → DshNative.weatherQuery()，
 * 由 Kotlin 异步取数并在宠物旁弹天气气泡。
 *
 * 为什么用轮询：菜单是 React 动态创建的，节点重建时机不固定，轮询最稳。
 */
(function () {
  'use strict';
  var MENU_SEL = '.dsh-pet-menu';
  var COLUMN_SEL = '.dsh-pet-menu-column';
  var MARK = 'data-dsh-weather-item';
  var TICK_MS = 300;

  function injectCss() {
    if (document.querySelector('style[data-plugin-css="dsh-weather-menu-style"]') !== null) return;
    var tag = document.createElement('style');
    tag.dataset.plugin = 'weather-menu';
    tag.dataset.pluginCss = 'dsh-weather-menu-style';
    // 复用 dsh-pet 菜单项的原生样式类，只补一个悬停色，视觉与其它项一致
    tag.textContent = '.dsh-pet-menu-item[' + MARK + ']:hover{background:#EDF1F9}';
    document.head.appendChild(tag);
  }

  /** 给菜单第一列追加「查询天气」。已加过则跳过（防止重复）。 */
  function tryInject() {
    var menu = document.querySelector(MENU_SEL);
    if (menu === null) return;
    if (menu.querySelector('[' + MARK + ']') !== null) return;
    var col = menu.querySelector(COLUMN_SEL);
    if (col === null) return;
    // 菜单列还没填充内容时先等一轮（React 会继续 addChild）
    if (col.children.length === 0) return;

    injectCss();
    var item = document.createElement('div');
    item.className = 'dsh-pet-menu-item';
    item.setAttribute(MARK, '1');
    var label = document.createElement('span');
    label.textContent = '\u67E5\u8BE2\u5929\u6C14'; // 查询天气
    item.appendChild(label);
    item.addEventListener('click', function (e) {
      e.stopPropagation();
      e.preventDefault();
      // 收起菜单（模拟点菜单外空处：触发 JS 侧 document mousedown 处理器）
      try {
        document.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
      } catch (err) {}
      try {
        if (window.DshNative && typeof window.DshNative.weatherQuery === 'function') {
          window.DshNative.weatherQuery();
        }
      } catch (err) {
        console.warn('[weather-menu] weatherQuery 失败', err);
      }
    });
    col.appendChild(item);
    console.log('[weather-menu] 已注入「查询天气」菜单项');
    // 追加后菜单可能变高而出屏（宠物在屏幕底部时尤其明显）：立即做边界兜底
    keepInViewport(col);
    // 布局稳定后（React 可能再调一次位置）再兜一次
    setTimeout(function () { keepInViewport(col); }, 60);
    setTimeout(function () { keepInViewport(col); }, 200);
  }

  /**
   * 菜单列越界修正：只改 margin-top / margin-left（不动 top/left，避免与 JS 的
   * clamp 定位逻辑打架；列是 position:absolute，margin 会随定位偏移生效）。
   * 上边超界 → 往下推；下边超界 → 往上提（提到能露出「查询天气」为止）。
   */
  function keepInViewport(col) {
    try {
      if (!col || !col.style) return;
      // 先清掉上一次的修正量，按原始位置重新量
      col.style.marginTop = '';
      col.style.marginLeft = '';
      var r = col.getBoundingClientRect();
      var vh = window.innerHeight;
      var vw = window.innerWidth;
      var pad = 8;
      var dy = 0;
      if (r.bottom > vh - pad) dy = (vh - pad) - r.bottom;   // 下越界：上提
      if (r.top + dy < pad) dy = pad - r.top;                // 上越界优先：下推
      if (dy !== 0) col.style.marginTop = dy + 'px';
      // 横向同理（列有 min-width 150，右侧宠物贴边时可能出屏）
      var dx = 0;
      if (r.right > vw - pad) dx = (vw - pad) - r.right;
      if (r.left + dx < pad) dx = pad - r.left;
      if (dx !== 0) col.style.marginLeft = dx + 'px';
      // 位置变了 → 请 Kotlin 重读菜单矩形（否则触摸层仍按旧矩形判内外，点不到菜单）
      if ((dy !== 0 || dx !== 0) && window.DshNative &&
          typeof window.DshNative.setMenuOpen === 'function') {
        window.DshNative.setMenuOpen(true);
      }
    } catch (e) {}
  }

  setInterval(tryInject, TICK_MS);
  console.log('[weather-menu] 菜单天气入口补丁已挂载');
})();
