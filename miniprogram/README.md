# 量化选股小程序

基于 Taro 3 + React 开发的微信小程序，展示量化平台的股票推荐。

## 快速开始

### 1. 安装依赖
```bash
cd miniprogram
npm install
```

### 2. 开发模式（watch）
```bash
npm run dev:weapp
```

### 3. 生产构建
```bash
npm run build:weapp
```

构建产物在 `dist/` 目录，用微信开发者工具打开 `miniprogram/` 目录即可预览。

### 4. 后端配置

小程序需要后端服务运行在 `localhost:8080`。后端新增了两个文件：
- `MpAuthFilter.java` — 拦截 `/api/mp/**`，校验 `X-MP-Token` 请求头
- `MpRecommendationController.java` — 精简推荐接口

Token 默认值 `quant-mp-2026-secret`，可在 `application.yml` 中通过 `mp.token` 配置。

### 5. 微信开发者工具设置

开发阶段，在开发者工具中勾选：
- 详情 → 本地设置 → **不校验合法域名**

## 页面结构

| 页面 | 路径 | 说明 |
|------|------|------|
| 推荐列表 | `pages/list` | 首页，策略/日期切换，大盘指数，推荐列表 |
| 个股详情 | `pages/detail` | 交易计划、多维度评分、买入理由、收益追踪 |
| 历史表现 | `pages/history` | 策略置信度、批次历史表现 |
| 关于 | `pages/about` | 版本信息和风险提示 |

## API 接口

### 小程序专用接口（需 Token）
| 接口 | 说明 |
|------|------|
| `GET /api/mp/recommendations/strategies` | 策略列表 |
| `GET /api/mp/recommendations/dates?strategyId=&days=` | 可用日期 |
| `GET /api/mp/recommendations/strategy/{id}/date/{date}` | 推荐列表 |
| `GET /api/mp/recommendations/latest?strategyId=` | 最新推荐 |

### 复用现有接口（无需额外鉴权）
| 接口 | 说明 |
|------|------|
| `GET /api/recommendations/batch-history` | 批次历史 |
| `GET /api/recommendations/hit-rate/strategy/{id}/date/{date}` | 命中率 |
| `GET /api/strategy-confidence/latest-all` | 置信度列表 |
| `GET /api/monitor/indices` | 大盘指数 |

## 技术栈
- Taro 3.6.35 + React 18
- Webpack 5.88.2
- Sass 样式
- 微信小程序平台（weapp）

## 上线清单
1. [ ] 域名备案 + HTTPS 证书
2. [ ] Nginx 反向代理 → localhost:8080
3. [ ] 小程序后台配置服务器域名白名单
4. [ ] 修改 `config/prod.js` 中 `BASE_URL`
5. [ ] 修改 `project.config.json` 中 `appid` 为正式 AppID
6. [ ] 修改 `src/utils/request.js` 中 `MP_TOKEN` 为正式密钥
7. [ ] `application.yml` 中配置 `mp.token` 为相同密钥
