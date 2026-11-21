# -*- coding: utf-8 -*-
"""生成毕业论文第 4 章插图（黑白印刷风格，200 DPI PNG）。
运行：python gen_figures.py   输出到脚本所在目录。
注意：SimHei 无 U+2212（−）字形，负号一律用 ASCII "-"。
"""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, Polygon, FancyArrowPatch
from pathlib import Path

plt.rcParams["font.family"] = "sans-serif"
plt.rcParams["font.sans-serif"] = ["SimHei", "Microsoft YaHei"]
plt.rcParams["axes.unicode_minus"] = False

EC = "#1a1a1a"   # 边线
FC = "#ffffff"   # 普通节点填充
GC = "#f2f2f2"   # 容器/结束节点填充
OUT = Path(__file__).resolve().parent


def new_ax(xmax, ymax, w=None, h=None):
    w = w or xmax / 1.35
    h = h or ymax / 1.35
    fig, ax = plt.subplots(figsize=(w, h))
    ax.set_xlim(0, xmax)
    ax.set_ylim(0, ymax)
    ax.axis("off")
    return fig, ax


def rect(ax, cx, cy, w, h, text, fs=10.5, fill=FC, lw=1.1, dashed=False, bold=False):
    b = FancyBboxPatch((cx - w / 2, cy - h / 2), w, h,
                       boxstyle="square,pad=0.02", fc=fill, ec=EC, lw=lw,
                       linestyle=(0, (4, 3)) if dashed else "solid")
    ax.add_patch(b)
    ax.text(cx, cy, text, ha="center", va="center", fontsize=fs,
            fontweight="bold" if bold else "normal", linespacing=1.45, color="#000")
    return (cx, cy, w, h)


def diamond(ax, cx, cy, w, h, text, fs=10, lw=1.1):
    pts = [(cx, cy + h / 2), (cx + w / 2, cy), (cx, cy - h / 2), (cx - w / 2, cy)]
    ax.add_patch(Polygon(pts, closed=True, fc=FC, ec=EC, lw=lw))
    ax.text(cx, cy, text, ha="center", va="center", fontsize=fs, linespacing=1.35)


def edge(ax, p1, p2, label=None, lxy=None, fs=9.5, cs=None, lw=1.1, dashed=False):
    a = FancyArrowPatch(p1, p2, arrowstyle="-|>", mutation_scale=13, lw=lw,
                        color=EC, connectionstyle=cs,
                        linestyle=(0, (4, 3)) if dashed else "solid",
                        shrinkA=2, shrinkB=2)
    ax.add_patch(a)
    if label:
        x, y = lxy if lxy else ((p1[0] + p2[0]) / 2, (p1[1] + p2[1]) / 2)
        ax.text(x, y, label, ha="center", va="center", fontsize=fs,
                bbox=dict(fc="white", ec="none", pad=0.5))


def save(fig, name):
    p = OUT / name
    fig.savefig(p, dpi=200, bbox_inches="tight", facecolor="white", pad_inches=0.15)
    plt.close(fig)
    print("saved", p.name)


# ---------------------------------------------------------------- 图 4.1 系统架构图
def fig_arch():
    fig, ax = new_ax(10, 9.4)

    # 浏览器层
    rect(ax, 5, 7.55, 9.0, 2.9, "", fill=GC)
    ax.text(5, 8.75, "浏览器（用户端）", ha="center", fontsize=11.5, fontweight="bold")
    row = [("功能页面视图\n（10 个功能页面）", 1.75, 2.35), ("Element Plus\n组件库", 4.1, 1.85),
           ("ECharts\n图表库", 6.15, 1.65), ("Pinia / Vue Router\n状态与路由", 8.3, 2.3)]
    for t, x, w in row:
        rect(ax, x, 7.35, w, 1.15, t, fs=9.5)

    edge(ax, (5, 6.05), (5, 5.55))
    ax.text(5.25, 5.8, "REST API（JSON）· JWT Bearer 鉴权（开发期经 Vite 代理转发）",
            ha="left", va="center", fontsize=9.5)

    # Spring Boot 层
    rect(ax, 5, 3.85, 9.0, 3.35, "", fill=GC)
    ax.text(5, 5.15, "Spring Boot 应用（经典四层架构）", ha="center", fontsize=11.5, fontweight="bold")
    row2 = [("Controller\n接口层\n参数校验\n出参组装", 1.72), ("Service\n业务层\n业务规则\n@Transactional", 3.92),
            ("Mapper\n数据访问层\nMyBatis-Plus\n参数化 SQL", 6.12), ("Common / Config\n统一返回\n全局异常\nJWT 拦截·CORS", 8.32)]
    for t, x in row2:
        rect(ax, x, 3.85, 2.02, 2.0, t, fs=9.3)

    edge(ax, (5, 2.12), (5, 1.72))
    ax.text(5.25, 1.92, "JDBC（MySQL Connector）", ha="left", va="center", fontsize=9.5)

    rect(ax, 5, 0.9, 8.8, 1.5,
         "MySQL 8 数据库（balancewisedb 库）\nuser · account · category · transaction_record · import_batch\n（各表逻辑外键关联 user_id，按用户严格隔离）", fs=9.5)
    save(fig, "fig4-1-arch.png")


# ---------------------------------------------------------------- 图 4.2 E-R 图
def fig_er():
    fig, ax = new_ax(12.6, 8.0)

    def entity(x, y, name, table, w=2.0, h=1.0):
        rect(ax, x, y, w, h, f"{name}\n({table})", fs=10)

    entity(1.2, 4.0, "用户", "user")
    entity(5.9, 6.6, "账户", "account")
    entity(5.9, 5.4, "分类", "category")
    entity(5.9, 2.4, "导入批次", "import_batch")
    entity(10.9, 4.0, "交易记录", "transaction_record", w=2.9)

    d1 = [(3.4, 6.6, "拥有"), (3.4, 5.4, "使用"), (3.4, 4.0, "产生"), (3.4, 2.4, "发起")]
    for x, y, t in d1:
        diamond(ax, x, y, 1.5, 0.85, t, fs=10)

    d2 = [(8.0, 6.6, "转出 / 转入"), (8.0, 5.4, "标注"), (8.0, 2.4, "包含")]
    for x, y, t in d2:
        diamond(ax, x, y, 1.8, 0.85, t, fs=9.5)

    # 用户 → 一级菱形（标签沿线分散放置）
    for _, y, _t in d1:
        edge(ax, (2.2, 4.0), (2.65, y))
        ly = 4.0 + 0.45 * (y - 4.0)
        ax.text(2.42, ly + (0.16 if y >= 4.0 else -0.16), "1", fontsize=9.5, ha="center")
    # 一级菱形 → 实体
    for _, y, _t in d1:
        edge(ax, (4.15, y), (4.9, y))
        ax.text(4.52, y + 0.2, "n", fontsize=9.5, ha="center")
    # 实体 → 二级菱形
    for _, y, _t in d2:
        edge(ax, (6.9, y), (7.1, y))
        ax.text(7.02, y + 0.2, "1", fontsize=9.5, ha="center")
    # 二级菱形 → 交易记录（直角折线）
    edge(ax, (8.9, 6.6), (10.9, 4.5), cs="angle,angleA=0,angleB=90")
    ax.text(9.35, 6.8, "n", fontsize=9.5, ha="center")
    edge(ax, (8.9, 5.4), (10.9, 4.5), cs="angle,angleA=0,angleB=90")
    ax.text(9.35, 5.6, "n", fontsize=9.5, ha="center")
    edge(ax, (8.9, 2.4), (10.9, 3.5), cs="angle,angleA=0,angleB=90")
    ax.text(9.35, 2.2, "n", fontsize=9.5, ha="center")
    # 用户 → 产生 → 交易记录（直连横线）
    edge(ax, (4.15, 4.0), (9.45, 4.0))
    ax.text(9.0, 4.2, "n", fontsize=9.5, ha="center")

    ax.text(6.0, 0.7, "注：各实体属性见 4.2.2 节表结构定义；账户经「转出 / 转入」两个方向与交易记录关联。",
            ha="center", fontsize=9.5, color="#333")
    save(fig, "fig4-2-er.png")


# ---------------------------------------------------------------- 图 4.3 记账与余额联动流程图
def fig_record_flow():
    fig, ax = new_ax(12.4, 12.0)

    rect(ax, 3.5, 11.2, 5.8, 0.65, "开始：用户提交记账请求（支出 / 收入 / 转账）")
    edge(ax, (3.5, 10.87), (3.5, 10.63))
    rect(ax, 3.5, 10.05, 7.2, 0.95, "Controller 参数校验\n金额 > 0 · 账户归属当前用户 · 转账两账户不同 · 账户未归档", fs=9.5)
    edge(ax, (3.5, 9.57), (3.5, 9.18))
    diamond(ax, 3.5, 8.5, 2.3, 1.15, "校验通过？")
    rect(ax, 9.3, 8.5, 4.4, 0.95, "返回统一错误信息\nResult{code≠0, message}", fs=9.5)
    edge(ax, (4.65, 8.5), (7.1, 8.5), "否", (5.75, 8.72))
    rect(ax, 9.3, 7.35, 4.4, 0.6, "结束（本次操作失败）", fill=GC)
    edge(ax, (9.3, 8.02), (9.3, 7.65))
    edge(ax, (3.5, 7.92), (3.5, 7.4), "是", (3.78, 7.65))

    rect(ax, 3.5, 6.85, 5.8, 0.7, "Service 开启数据库事务（@Transactional）")
    edge(ax, (3.5, 6.5), (3.5, 6.2))
    rect(ax, 3.5, 5.6, 5.8, 1.0, "插入 transaction_record\n支出记 from · 收入记 to · 转账记 from + to", fs=9.5)
    edge(ax, (6.4, 5.6), (8.55, 5.6), dashed=True)
    rect(ax, 10.45, 5.6, 3.5, 1.0,
         "余额一律 SQL 原子增减\nUPDATE account SET\nbalance = balance ± δ", fs=9.3, dashed=True)

    # 三分支（按类型联动更新余额）
    edge(ax, (3.5, 5.1), (3.5, 4.8))
    ax.plot([1.7, 8.3], [4.8, 4.8], color=EC, lw=1.1)
    for x in (1.7, 5.0, 8.3):
        edge(ax, (x, 4.8), (x, 4.45))
    ax.text(6.65, 4.62, "按类型联动更新余额", ha="center", va="center", fontsize=9.5)
    rect(ax, 1.7, 3.8, 3.0, 1.2, "支出\nfrom 账户\nbalance - amount", fs=9.5)
    rect(ax, 5.0, 3.8, 2.6, 1.2, "收入\nto 账户\nbalance + amount", fs=9.5)
    rect(ax, 8.3, 3.8, 3.0, 1.2, "转账\nfrom - amount\nto + amount", fs=9.5)

    # 汇合到成功判断
    for x in (1.7, 8.3):
        edge(ax, (x, 3.2), (x, 2.85), cs="angle,angleA=90,angleB=0")
    edge(ax, (5.0, 3.2), (5.0, 2.85))
    ax.plot([1.7, 8.3], [2.85, 2.85], color=EC, lw=1.1)
    edge(ax, (5.0, 2.85), (5.0, 2.4))

    diamond(ax, 5.0, 1.7, 2.3, 1.05, "执行成功？")
    rect(ax, 9.6, 1.7, 4.2, 0.95, "整体回滚事务\n余额与流水同生共死", fs=9.5)
    edge(ax, (6.15, 1.7), (7.5, 1.7), "否", (6.8, 1.92))
    rect(ax, 9.6, 0.5, 3.6, 0.6, "结束（失败）", fill=GC)
    edge(ax, (9.6, 1.22), (9.6, 0.8))
    edge(ax, (5.0, 1.17), (5.0, 0.8), "是", (5.28, 0.98))
    rect(ax, 5.0, 0.45, 4.4, 0.65, "提交事务 · 返回成功", fill=GC)
    save(fig, "fig4-3-record-flow.png")


# ---------------------------------------------------------------- 图 4.4 账单导入流程图
def fig_import_flow():
    fig, ax = new_ax(13.0, 13.0)
    X, W = 4.3, 6.0

    rect(ax, X, 12.2, W + 1.0, 0.65, "开始：选择平台（支付宝 / 微信）并上传 CSV 文件（≤ 10 MB）", fs=9.8)
    edge(ax, (X, 11.87), (X, 11.6))
    rect(ax, X, 11.15, W, 0.85, "按平台选择解析器\nAlipayParser / WechatParser（统一接口）", fs=9.5)
    edge(ax, (X + W / 2, 11.15), (X + W / 2 + 0.3, 11.15), dashed=True)
    rect(ax, 9.45, 11.15, 3.5, 0.85, "文件非 CSV 或表头与平台\n不符 → 返回明确提示", fs=9.3, dashed=True)
    edge(ax, (X, 10.72), (X, 10.45))
    rect(ax, X, 9.95, W, 0.9, "解析为标准化行并清洗过滤\n剔除非成功交易与非法金额行，记录剔除原因", fs=9.5)
    edge(ax, (X, 9.5), (X, 9.23))
    rect(ax, X, 8.75, W, 0.85, "分类自动映射\n平台交易分类 → 本系统分类，无匹配归「未分类」", fs=9.5)
    edge(ax, (X, 8.32), (X, 8.05))
    rect(ax, X, 7.55, W, 0.85, "入账账户匹配\n按支付方式匹配用户账户，匹配不到留空待指定", fs=9.5)
    edge(ax, (X, 7.12), (X, 6.85))
    rect(ax, X, 6.3, W, 1.05, "重复检测\nexternal_id 比对库中未删除记录与批内记录\n重复行标记并默认不勾选", fs=9.5)
    edge(ax, (X, 5.77), (X, 5.5))
    rect(ax, X, 5.05, W, 0.75, "生成导入预览（不落库）")
    edge(ax, (X, 4.67), (X, 4.4))
    rect(ax, X, 3.95, W, 0.85, "用户预览确认\n可修改分类 / 账户，勾选需入账的行", fs=9.5)
    edge(ax, (X, 3.52), (X, 3.25))
    rect(ax, X, 2.6, W, 1.2, "确认入账（单事务）\n插入 import_batch → 批量插入流水 → 逐条更新余额\n批次状态置 confirmed", fs=9.5)
    edge(ax, (X + W / 2, 2.6), (X + W / 2 + 0.3, 2.6), dashed=True)
    rect(ax, 9.45, 2.6, 3.5, 0.85, "任一步失败\n整体回滚", fs=9.3, dashed=True)
    edge(ax, (X, 2.0), (X, 1.73))
    rect(ax, X, 1.3, W + 1.0, 0.7, "结束：批次入账完成，流水与余额一致、来源可追溯", fs=9.8, fill=GC)
    save(fig, "fig4-4-import-flow.png")


# ---------------------------------------------------------------- 图 4.5 批次撤销流程图
def fig_rollback_flow():
    fig, ax = new_ax(12.4, 9.4)
    X = 4.0

    rect(ax, X, 8.55, 5.0, 0.65, "开始：用户发起批次撤销")
    edge(ax, (X, 8.22), (X, 8.0))
    rect(ax, X, 7.6, 5.6, 0.7, "校验批次归属当前用户")
    edge(ax, (X, 7.25), (X, 6.85))
    diamond(ax, X, 5.75, 4.4, 1.7, "状态 = confirmed？\n（条件更新抢占·防并发重复撤销）", fs=9.3)
    edge(ax, (X + 2.2, 5.75), (X + 3.0, 5.75), "否", (X + 2.6, 5.97))
    rect(ax, 9.55, 5.75, 4.3, 0.85, "拒绝撤销：批次不存在、\n越权或已撤销", fs=9.5)
    rect(ax, 9.55, 4.55, 3.6, 0.6, "结束", fill=GC)
    edge(ax, (9.55, 5.32), (9.55, 4.85))
    edge(ax, (X, 4.9), (X, 4.6), "是", (X + 0.28, 4.75))
    rect(ax, X, 4.15, 5.6, 0.8, "批次内全部流水软删除（is_deleted = 1）", fs=9.5)
    edge(ax, (X, 3.75), (X, 3.5))
    rect(ax, X, 3.0, 5.6, 0.85, "逐条反向冲销余额\nfrom 加回 · to 减回", fs=9.5)
    edge(ax, (X + 2.8, 3.0), (X + 3.35, 3.0), dashed=True)
    rect(ax, 9.85, 3.0, 3.7, 0.85, "全程单事务\n任一步失败整体回滚", fs=9.3, dashed=True)
    edge(ax, (X, 2.57), (X, 2.35))
    rect(ax, X, 1.95, 5.6, 0.7, "批次置 rolled_back，记录 rolled_back_at", fs=9.5)
    edge(ax, (X, 1.6), (X, 1.38))
    rect(ax, X, 0.95, 5.8, 0.7, "结束：余额完整还原，原文件可重新导入", fs=9.8, fill=GC)
    save(fig, "fig4-5-rollback-flow.png")


if __name__ == "__main__":
    fig_arch()
    fig_er()
    fig_record_flow()
    fig_import_flow()
    fig_rollback_flow()
    print("all done")
