# 贡献指南

感谢你对 Quant Platform 的关注！无论是修一个错别字、补一段文档，还是提一个新因子、新策略，都非常欢迎。

本项目处于早期阶段，**文档类贡献来者不拒**，代码类贡献请先开 Issue 讨论。

---

## 行为准则

请保持友善、尊重。我们追求的是一个让量化学习者和研究者都感到舒适的技术社区。

- 对事不对人，讨论聚焦技术本身
- 新手问题耐心回答（你曾经也是新手）
- 不贬低他人的代码风格或问题水平
- 欢迎不同背景的贡献者

---

## 我能贡献什么？

| 贡献类型 | 难度 | 是否需要先开 Issue | 说明 |
|---------|------|------------------|------|
| 📝 文档修正（错别字、表述优化） | ⭐ | 不需要 | 直接提 PR |
| 📖 文档新增（教程、FAQ、案例） | ⭐⭐ | 建议先开 Issue | 避免和已有规划重复 |
| 🐛 Bug 修复 | ⭐⭐ | 已有 Issue 直接认领，没有先开 Issue | 描述清楚复现步骤 |
| ✨ 新因子实现 | ⭐⭐⭐ | **必须先开 Issue** | 需讨论因子定义和实现方式 |
| 🎯 新策略实现 | ⭐⭐⭐ | **必须先开 Issue** | 需讨论策略逻辑和回测验证 |
| 🔧 架构改进 / 重构 | ⭐⭐⭐⭐ | **必须先开 Issue** | 需讨论方案和影响范围 |
| 🎨 UI / 交互优化 | ⭐⭐ | 建议先开 Issue | 附截图说明改进点 |

---

## 贡献流程

### 1. Fork 并 Clone

```bash
# 在 GitHub 上 Fork 本仓库
# 然后 clone 你 fork 的仓库
git clone https://github.com/<你的用户名>/quant-platform.git
cd quant-platform

# 添加上游远程
git remote add upstream https://github.com/<原作者>/quant-platform.git
```

### 2. 创建分支

```bash
# 从 main 创建功能分支
git checkout -b fix/typo-in-readme
# 或
git checkout -b feature/new-momentum-factor
```

**分支命名规范**：
- `fix/` — Bug 修复
- `feature/` — 新功能
- `docs/` — 文档
- `refactor/` — 重构

### 3. 编写代码 / 文档

**代码规范**：
- Java：遵循 [Google Java Style](https://google.github.io/styleguide/javaguide.html)，4 空格缩进
- React：使用函数组件 + Hooks，不用 class 组件
- Python：遵循 PEP 8，4 空格缩进
- 所有新增公开方法需有 Javadoc / 注释

**提交信息规范**（参考 [Conventional Commits](https://www.conventionalcommits.org/)）：

```
<type>(<scope>): <subject>

<body>
```

- `type`：feat / fix / docs / refactor / test / chore
- `scope`：可选，如 factor / strategy / backtest / frontend
- `subject`：简明描述，不超过 50 字

示例：
```
feat(factor): 新增 FIN_GROSS_MARGIN 毛利率因子

在 builtinCalculators 中注册毛利率因子，IC 分析显示 +0.024。
```

### 4. 测试

- **Java**：`mvn test`，确保已有测试通过
- **前端**：`cd frontend && npm test`
- **新增因子 / 策略**：必须附 IC 分析或回测结果截图，证明逻辑正确
- **Bug 修复**：描述复现步骤和修复方案

### 5. 提交 PR

```bash
git push origin fix/typo-in-readme
```

然后在 GitHub 上发起 Pull Request，目标分支为 `main`。

PR 模板会引导你填写：
- 改动说明
- 改动类型
- 测试情况
- 关联 Issue

---

## PR 审查标准

| 维度 | 要求 |
|------|------|
| **功能正确** | 改动达到预期效果，不引入回归 |
| **测试通过** | 已有测试不 break，新增功能有测试覆盖 |
| **代码风格** | 符合上述规范，无明显坏味道 |
| **文档同步** | 如果改了功能，README / 文档需同步更新 |
| **合规** | 不引入荐股 / 承诺收益等违规内容 |

**审查流程**：
1. 维护者会在 48 小时内首次响应
2. 可能提出修改建议，请根据反馈调整（直接 push 到同一分支即可）
3. 通过后合并

---

## 新因子贡献说明

如果你实现了一个新因子，请在 PR 中包含：

1. **因子定义**：计算公式、数据来源
2. **分类**：动量 / 价值 / 质量 / 情绪 / 资金 / 形态
3. **IC 分析结果**：至少 1 年的 IC 序列、ICIR、RankIC
4. **因子值分布**：覆盖度、极值情况
5. **与其他因子的相关性**：是否高度冗余

因子代码位置：
- 内置因子：`backend/factor/.../builtinCalculators`
- 脚本因子：通过前端因子编辑器，使用 Groovy 脚本

---

## 新策略贡献说明

新策略需包含：

1. **策略逻辑**：选股规则、调仓频率、风控规则
2. **回测结果**：年化收益、最大回撤、Sharpe、胜率
3. **与现有策略的差异**：避免和 14 套已有策略重复
4. **适用市场环境**：牛市 / 震荡 / 熊市的表现

---

## 本地开发环境

参考 [docs/quickstart.md](docs/quickstart.md) 搭建本地环境。

**开发建议**：
- 后端调试：IDE 启动 `QuantPlatformApplication`，开启热加载
- 前端调试：`npm start` 默认在 3000 端口，代理到 8080 后端
- 改了 `common` 模块后，需要先 `mvn -pl common install` 再构建 backend

---

## 致谢

所有贡献者都会在 README 的 Contributors 区列出。你的第一个 PR 合并后，维护者会添加你的名字——这是最强的社区激励。

---

## 有问题？

- 提交 [Issue](https://github.com/<your-username>/quant-platform/issues/new/choose)
- 加群交流：微信群二维码见 README

再次感谢你的贡献！🎯
