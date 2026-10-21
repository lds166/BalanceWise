/* ========== BalanceWise 原型公共脚本 ========== */

/* 侧边导航；active 为当前页标识 */
function renderNav(active) {
  const items = [
    { key: "index",        ico: "🏠", text: "首页仪表盘", href: "index.html" },
    { key: "record",       ico: "✏️", text: "记一笔",     href: "record.html" },
    { key: "transactions", ico: "📒", text: "流水",       href: "transactions.html" },
    { key: "import",       ico: "📥", text: "账单导入",   href: "import.html" },
    { key: "accounts",     ico: "💳", text: "账户管理",   href: "accounts.html" },
    { key: "categories",   ico: "🏷️", text: "分类管理",   href: "categories.html" },
    { key: "stats",        ico: "📊", text: "统计报表",   href: "stats.html" },
  ];
  const nav = document.getElementById("nav");
  nav.innerHTML = items
    .map(i => `<a href="${i.href}" class="${i.key === active ? "active" : ""}"><span class="ico">${i.ico}</span>${i.text}</a>`)
    .join("") +
    `<a href="login.html" style="margin-top:20px"><span class="ico">↩️</span>退出登录</a>`;
}

/* 页面骨架：侧边栏 + 顶栏 */
function renderLayout(active, title) {
  document.body.insertAdjacentHTML("afterbegin", `
    <div class="layout">
      <aside class="sidebar">
        <div class="logo"><span class="mark">账</span>BalanceWise</div>
        <nav id="nav"></nav>
      </aside>
      <div class="content">
        <header class="topbar">
          <h1>${title}</h1>
          <div class="user"><span class="avatar">${USER.nickname[0]}</span><span>${USER.nickname}</span></div>
        </header>
        <div class="page" id="page"></div>
      </div>
    </div>
    <div id="toast"></div>`);
  renderNav(active);
}

function toast(msg) {
  const el = document.getElementById("toast");
  el.textContent = msg;
  el.classList.add("show");
  clearTimeout(el._t);
  el._t = setTimeout(() => el.classList.remove("show"), 2200);
}

/* 净资产 = 全部账户余额求和（负债为负） */
function netWorth() {
  return ACCOUNTS.reduce((s, a) => s + a.balance, 0);
}

function typeTag(t) {
  if (t === "income") return '<span class="tag green">收入</span>';
  if (t === "expense") return '<span class="tag red">支出</span>';
  return '<span class="tag gray">转账</span>';
}

function typeText(t) {
  return t === "income" ? "收入" : t === "expense" ? "支出" : "转账";
}
