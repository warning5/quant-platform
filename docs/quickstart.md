# 5 分钟快速启动

> 目标：从 clone 到看到界面，最快路径。如果你想深入理解架构，请看 [README](../README.md) 的技术架构章节。

---

## 前置条件

开始前请确认已安装以下环境：

| 依赖 | 版本要求 | 验证命令 | 下载地址 |
|------|---------|---------|---------|
| Java | 21+ | `java -version` | [Temurin 21](https://adoptium.net/) |
| Maven | 3.8+ | `mvn -version` | [maven.apache.org](https://maven.apache.org/download.cgi) |
| Node.js | 18+ | `node -v` | [nodejs.org](https://nodejs.org/) |
| MySQL | 8.0+ | `mysql --version` | [mysql.com](https://dev.mysql.com/downloads/) |
| Python | 3.13+ | `python --version` | [python.org](https://www.python.org/) |
| ClickHouse | 24.x+ | `clickhouse-client --version` | [clickhouse.com](https://clickhouse.com/) |

> **ClickHouse 是必需依赖**：日线行情、因子值、因子收益等时序数据存储在 ClickHouse。MySQL 只存元数据（股票信息、策略定义、回测记录等），两者各司其职，缺一不可。

---

## 第 1 步：获取代码（30 秒）

```bash
git clone https://github.com/<your-username>/quant-platform.git
cd quant-platform
```

## 第 2 步：初始化数据库（2 分钟）

> ⚠️ **重要：表结构不会自动创建。** 项目使用 MyBatis-Plus，**应用启动时不执行任何 DDL**。数据库脚本位于 `backend/sql/`，分 MySQL 和 ClickHouse 两份：
> - `mysql-stock-YYYYMMDD.7z` — MySQL `stock` 库完整备份（含表结构 + 14 套策略种子数据 + 历史行情），约 260MB 压缩包
> - `ch.sql` — ClickHouse 表结构脚本（仅建表，数据由应用运行时写入）

### 2a. 导入 MySQL（必需）

**1. 解压备份文件**

```bash
# 用 7-Zip / WinRAR / Bandizip 解压 backend/sql/mysql-stock-*.7z
# 解压后得到一个 .sql 文件（如 mysql-stock-20260719.sql）
```

**2. 导入到 MySQL**

```bash
# 方式 A：命令行导入（推荐，大文件稳定）
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS stock CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -u root -p stock < backend/sql/mysql-stock-20260719.sql

# 方式 B：进入 mysql 客户端后用 source
mysql -u root -p
mysql> CREATE DATABASE IF NOT EXISTS stock CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
mysql> USE stock;
mysql> SOURCE backend/sql/mysql-stock-20260719.sql;
```

> 备份文件已含 `stock_info`（5000+ 股票）、`strategy_definition`（14 套策略）、`factor_definition`（35 个因子）等种子数据，导入后即可直接启动体验，无需再采集基础数据。

### 2b. 导入 ClickHouse（必需）

ClickHouse 用于存储日线行情（`stock_daily`）、因子值（`factor_value`）、因子收益（`factor_premium`）等时序数据，是系统运行的必需依赖。部署 ClickHouse 后执行建表脚本：

```bash
# HTTP 接口（默认 8123 端口）
clickhouse-client --host 172.19.72.140 --port 9000 --user default --password 123456 \
  --multiquery < backend/sql/ch.sql
```

> `ch.sql` 仅创建表结构（如 `stock_daily`、`factor_value`、`factor_premium` 等），不含数据。行情数据需要启动应用后通过「数据更新」页面采集。

### 2c. 配置 .env 文件（可选，30 秒）

`application.yml` 已带开发环境默认值（`MYSQL_PASSWORD=123456`、`CLICKHOUSE_HOST=172.19.72.140` 等）。如果你的 MySQL 密码不是 `123456`，或 ClickHouse 部署在不同地址，需要创建 `.env` 文件覆盖默认值。

`.env` 文件**已被 `.gitignore` 排除**，新 clone 的项目里没有，需要自建：

```bash
# 在 backend/src/main/resources/ 下创建 .env 文件
# 内容参考（按需修改）：
DB_BACKEND=clickhouse              # 数据后端（时序数据走 ClickHouse）
DATA_SOURCE=baostock               # 数据源：baostock | tencent
MYSQL_HOST=localhost
MYSQL_PORT=3306
MYSQL_USER=root
MYSQL_PASSWORD=你的MySQL密码
MYSQL_DATABASE=stock
CLICKHOUSE_HOST=172.19.72.140
CLICKHOUSE_PORT=8123
CLICKHOUSE_USER=default
CLICKHOUSE_PASSWORD=123456
CLICKHOUSE_DATABASE=stock
```

> 加载机制：`DotenvLoader`（`EnvironmentPostProcessor`）在 Spring 启动前从 classpath `.env` → 当前目录 `.env` → `../.env` → `../../.env` 按顺序加载，外部文件可覆盖 classpath。

## 第 3 步：启动后端（2 分钟）

```bash
# 编译（首次会下载依赖，耐心等）
mvn clean package -DskipTests

# 启动
java -jar backend/target/backend-1.0.0.jar
```

看到以下日志说明启动成功：

```
Started QuantPlatformApplication in xx seconds
```

验证：浏览器打开 `http://localhost:8080/api/swagger-ui.html`，能看到 Swagger 接口文档即成功。

## 第 4 步：启动前端（1 分钟）

```bash
# 新开一个终端
cd frontend
npm install
npm start
```

看到以下输出说明启动成功：

```
  Local:   http://localhost:3000
```

浏览器自动打开 `http://localhost:3000`，看到 Dashboard 界面即完成启动。

## 第 5 步：采集数据（可选，30 秒看效果）

刚启动时界面是空的，需要采集行情数据。在系统界面的「数据更新」页面点击「执行」，或通过 API 触发：

```bash
# 触发一次日线数据采集（沪深市场）
curl -X POST http://localhost:8080/api/data/schedule/trigger/DAILY
```

采集完成后，Dashboard 和各功能页就会有数据展示。

---

## ✅ 启动成功检查清单

- [ ] 后端启动无报错，Swagger 可访问
- [ ] 前端启动无报错，Dashboard 页面打开
- [ ] MySQL `stock` 库已导入表结构和种子数据
- [ ] ClickHouse 已执行 `ch.sql` 建表脚本
- [ ] 触发数据采集后，Dashboard 有数据展示

---

## 常见报错排查

### 1. 后端启动报数据库连接失败

```
Failed to obtain JDBC Connection
```

**原因**：MySQL 未启动，或密码不对。

**解决**：
```bash
# 检查 MySQL 是否运行
# Windows:
net start MySQL80

# 检查密码是否匹配 application.yml 中的配置
mysql -u root -p  # 用配置文件里的密码登录试试
```

### 2. 后端启动报端口被占用

```
Port 8080 was already in use
```

**解决**：杀掉占用 8080 的进程，或修改端口：
```bash
# 查找占用进程（Windows）
netstat -ano | findstr :8080
taskkill /PID <进程号> /F

# 或换端口启动
java -jar backend/target/backend-1.0.0.jar --server.port=8081
```

### 3. 前端 npm install 失败

```
npm ERR! network
```

**原因**：网络问题，npm 默认源较慢。

**解决**：切换淘宝镜像
```bash
npm config set registry https://registry.npmmirror.com
npm install
```

### 4. 前端启动报代理错误

```
Proxy error: Could not proxy request
```

**原因**：后端没启动，或端口不对。

**解决**：确认后端已在 8080 运行。前端代理配置在 `frontend/package.json` 的 `proxy` 字段，默认指向 `http://localhost:8080`。

### 5. Python 脚本报 Baostock 登录失败

```
bs.login() error: 黑名单用户
```

**原因**：Baostock 匿名登录按 IP 限流，高频请求会触发封禁。

**解决**：
- 等待几小时后重试，或更换网络（如开手机热点）
- 数据采集分批执行，每批 50 只股票，间隔 10 分钟

### 6. ClickHouse 连接失败

```
ClickHouse exception: Connection refused
```

**解决**：
- 确认 ClickHouse 服务已启动：`clickhouse-client --host <CH_HOST> --port 9000 --query "SELECT 1"`
- 检查 `.env` 或 `application.yml` 中 ClickHouse 连接地址和端口（native 默认 9000，HTTP 默认 8123）
- 确认 `ch.sql` 已执行，关键表（`stock_daily`、`factor_value`）已创建：
  ```bash
  clickhouse-client --host <CH_HOST> --query "SHOW TABLES FROM stock"
  ```

### 7. 表不存在

```
Table 'stock.xxx' doesn't exist
```

**原因**：未执行 `backend/sql/` 下的初始化脚本，或脚本执行不完整。

**解决**：
- MySQL：确认 `mysql-stock-*.7z` 已解压并导入 `stock` 库
- ClickHouse：确认 `ch.sql` 已执行，`SHOW TABLES FROM stock` 能列出 `stock_daily`、`factor_value` 等表
- 重新执行对应脚本（脚本均用 `IF NOT EXISTS` 写法，可重复执行）

---

## 下一步

启动成功后，建议按以下顺序体验：

1. **数据采集**：在「数据更新」页面执行一次全量采集
2. **因子计算**：采集完成后触发因子计算，查看「因子管理」页面的 IC 分析
3. **策略回测**：在「策略管理」选择一个策略，到「回测」页面跑一次历史回测
4. **AI 分析**：在「AI 分析」页面输入一只股票代码，体验 DeepSeek 智能诊断
5. **每日推荐**：在「推荐」页面查看 P1-P6 管线产出的选股推荐

更多功能说明见 [使用手册](../frontend/src/pages/manual)（启动后在界面内可直接访问）。

---

## 遇到问题？

- 📖 查看 [完整文档](../README.md)
- 🐛 提交 [Issue](https://github.com/<your-username>/quant-platform/issues/new/choose)（请选择对应模板）
- 💬 加群交流：（微信群二维码见 README）

---

*本平台仅供量化投资研究与学习使用，不构成任何投资建议。所有回测结果均为历史数据模拟，不代表未来表现。投资有风险，入市需谨慎。*
