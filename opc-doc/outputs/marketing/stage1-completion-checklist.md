# 阶段一完成清单：开源建信任

> 制定日期：2026-07-20
> 配套文档：`funnel-execution-plan.md` 第 1 节「开源建信任」
> 本文档：8 项信任资产的完成状态盘点 + 需手动操作的指引

---

## 8 项信任资产完成状态

| # | 资产 | 状态 | 完成项 | 待办项 |
|---|------|------|--------|--------|
| 1 | README 质量门 | ✅ 已完成 | 功能矩阵、架构图、技术栈、快速启动、免责声明、截图画廊入口、PRs Welcome badge；**已修正 license badge（MIT→Apache-2.0）** | 无 |
| 2 | 截图画廊 | 🟡 框架就绪 | `screenshots/README.md` 截图指南已创建（10 张清单 + 命名规范 + 截图技巧） | **需手动截图 10 张**（见下方指引） |
| 3 | 快速启动文档 | ✅ 已完成 | `docs/quickstart.md`（5 步启动 + 7 类常见报错排查 + 启动检查清单） | 无 |
| 4 | 正式 Release | 🟡 草稿就绪 | `docs/RELEASE_NOTES_v1.0.md`（功能清单 + 已知限制 + 路线图） | **需在 GitHub 打 tag 发布**（见下方指引） |
| 5 | Topics 标签 | ❌ 需手动设置 | — | **需在 GitHub 仓库设置**（见下方指引） |
| 6 | CONTRIBUTING.md | ✅ 已完成 | 贡献流程 + 代码规范 + 提交规范 + 新因子/策略贡献说明 + PR 审查标准 | 无 |
| 7 | Issue 模板 | ✅ 已完成 | `.github/ISSUE_TEMPLATE/` 下 3 个模板（bug_report / feature_request / question）+ PR 模板 | 无 |
| 8 | LICENSE + 免责声明 | ✅ 已完成 | LICENSE（Apache 2.0）已有；README 末尾免责声明已有 | 无 |

**总结**：8 项中 5 项已完成，2 项框架就绪待手动操作，1 项纯手动操作。

---

## 需要你手动完成的 3 件事

### 事项 1：截图 10 张（约 30 分钟）

**为什么不能自动做**：截图需要实际运行的平台界面 + 真实数据，必须在你本地操作。

**操作步骤**：

1. 启动平台（参考 `docs/quickstart.md`）
2. 确保有数据（跑一次数据采集 + 因子计算）
3. 按 `screenshots/README.md` 的清单，截 10 张图：
   - `01-dashboard.png` — Dashboard 总览
   - `02-factor-list.png` — 因子管理列表
   - `03-factor-ic.png` — 因子 IC 分析
   - `04-strategy.png` — 策略列表
   - `05-backtest.png` — 回测报告
   - `06-llm.png` — AI 智能分析
   - `07-screen.png` — 选股器
   - `08-monitor.png` — 模拟盘监控
   - `09-thermometer.png` — 市场温度计
   - `10-stock-analysis.png` — 个股深度分析
4. 保存到 `screenshots/` 目录（PNG 格式，1920×1080）
5. 截图完成后，README 中的截图区块会自动显示

**截图工具**：Windows 用 `Win + Shift + S` 或 [Snipaste](https://www.snipaste.com/)

**验证**：截完后在 GitHub 仓库主页看 README，截图应正常显示（不是破图）。

---

### 事项 2：在 GitHub 打 v1.0.0 tag 并发布 Release（约 10 分钟）

**操作步骤**：

1. 把所有新增文件提交并推送到 GitHub：
   ```bash
   git add .
   git commit -m "docs: 完成阶段一信任资产（README修正/截图画廊/快速启动/CONTRIBUTING/Issue模板/Release Notes）"
   git push origin main
   ```

2. 打 tag：
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```

3. 在 GitHub 仓库页面：
   - 点击右侧「Releases」→「Create a new release」
   - 选择 tag `v1.0.0`
   - Release title：`v1.0.0 - 首个正式版本`
   - 把 `docs/RELEASE_NOTES_v1.0.md` 的内容粘贴到描述框
   - 勾选「This is a latest release」
   - 点击「Publish release」

**验证**：GitHub 仓库主页右侧「Releases」栏显示 v1.0.0。

---

### 事项 3：设置 GitHub Topics 标签（约 2 分钟）

**操作步骤**：

1. 进入 GitHub 仓库主页
2. 在仓库名称下方，点击右侧的 ⚙️ 齿轮图标（About 旁边）
3. 在「Topics」字段添加以下标签（逐个输入，回车确认）：

   ```
   quantitative-finance
   stock
   factor-model
   backtesting
   a-share
   python
   spring-boot
   clickhouse
   react
   ai
   ```

4. 同时设置：
   - Description：`企业级 A 股量化投研一体化平台 | 35+因子 × 14策略 × AI分析`
   - Website：可填你的演示地址（如有）
5. 点击「Save changes」

**验证**：仓库主页 About 区显示这些 Topics 标签，可点击跳转搜索。

---

## 完成后的「信任温度计」自检

完成上述 3 件事后，对照执行手册的信任温度计自检：

| 信号 | 标准 | 你的状态 |
|------|------|---------|
| README 有截图 | 8-10 张前端截图 | 截图后达标 ✅ |
| 有正式 Release | Releases 栏显示 v1.0.0 | 打 tag 后达标 ✅ |
| Issues 有模板 | 提 issue 时能选模板 | 已达标 ✅ |
| 有 screenshots/ 目录 | 目录存在且有图 | 截图后达标 ✅ |
| 最近 commit 在 7 天内 | 有近期提交 | 推送后达标 ✅ |
| License 清晰 | Apache 2.0 | 已达标 ✅ |
| 有 CONTRIBUTING | 贡献指南存在 | 已达标 ✅ |

**目标**：达到 🟢 热（信任）状态——截图齐全、Release 最新、Issues 有问必答、最近 commit 3 天内。

---

## 阶段一完成定义（来自执行手册 1.5 节）

8 项资产做完只是必要条件，**阶段一真正完成的信号**是以下 5 项同时达标：

- [ ] 8 项信任资产全部完成（本清单已完成 5 项，3 项待手动操作）
- [ ] GitHub star 突破 50（需要推广引流，属于阶段二的工作）
- [ ] 有至少 1 个外部用户成功跑通项目并反馈
- [ ] 有至少 1 个 issue 由非自己提出
- [ ] 最近 4 周每周都有 commit

**结论**：8 项资产完成后，阶段一的「资产建设」部分就结束了。star 50+、外部用户跑通、外部 issue 这三项是**社区反馈信号**，需要靠持续的内容输出和社区运营来积累——这正好衔接阶段二「内容引流」。

---

## 本轮已创建/修改的文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `README.md` | 修改 | 修正 license badge（MIT→Apache-2.0）；新增 PRs Welcome badge；新增截图画廊入口区块 |
| `screenshots/README.md` | 新建 | 截图指南：10 张截图清单 + 命名规范 + 截图技巧 |
| `docs/quickstart.md` | 新建 | 5 分钟快速启动 + 7 类常见报错排查 |
| `CONTRIBUTING.md` | 新建 | 贡献指南：流程 + 规范 + 新因子/策略贡献说明 |
| `.github/ISSUE_TEMPLATE/bug_report.md` | 新建 | Bug 报告模板 |
| `.github/ISSUE_TEMPLATE/feature_request.md` | 新建 | 功能建议模板 |
| `.github/ISSUE_TEMPLATE/question.md` | 新建 | 提问模板 |
| `.github/PULL_REQUEST_TEMPLATE.md` | 新建 | PR 模板 |
| `docs/RELEASE_NOTES_v1.0.md` | 新建 | v1.0 Release Notes 草稿 |

---

## 合规检查

- ✅ 所有文档末尾有免责声明
- ✅ 无"最/第一/保证收益"等广告法极限词
- ✅ 未涉及荐股、承诺收益
- ✅ LICENSE 与 README badge 一致（Apache 2.0）
- ✅ Release Notes 明确标注"历史数据模拟不代表未来"

---

*完成上述 3 项手动操作后，阶段一资产建设即告完成，可启动阶段二「内容引流」。*
