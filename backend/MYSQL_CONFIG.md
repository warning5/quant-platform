# MySQL配置说明

本平台支持H2和MySQL两种数据库,默认使用H2数据库(适合开发测试)。

## 使用MySQL数据库

### 1. 创建数据库

首先在MySQL中创建数据库:

```sql
CREATE DATABASE quantdb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 2. 修改配置文件

编辑 `backend/src/main/resources/application-mysql.yml` 文件,修改以下配置:

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/quantdb?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    driver-class-name: com.mysql.cj.jdbc.Driver
    username: root        # 修改为你的MySQL用户名
    password: your_password  # 修改为你的MySQL密码
```

### 3. 启动应用

使用MySQL profile启动应用:

#### 方式1: Maven命令行启动

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```

#### 方式2: 修改IDE启动配置

在IDEA中,编辑运行配置,添加VM options或Environment variables:

- VM options: `-Dspring.profiles.active=mysql`

#### 方式3: 打包后启动

```bash
mvn clean package
java -jar target/quant-platform-1.0.0.jar --spring.profiles.active=mysql
```

### 4. 数据库初始化

应用启动时会自动执行 `schema.sql` 初始化数据库表结构。

如果需要手动执行schema文件:

```bash
mysql -u root -p quantdb < schema.sql
```

## 配置说明

### H2数据库 (默认)
- 文件路径: `./data/quantdb`
- 控制台访问: http://localhost:8080/api/h2-console
- 用户名: `sa`
- 密码: `quant2024`
- JDBC URL: `jdbc:h2:file:./data/quantdb`

### MySQL数据库 (生产环境推荐)
- 数据库名: `quantdb`
- 字符集: `utf8mb4`
- 时区: `Asia/Shanghai`
- 支持的MySQL版本: 8.0+

## 数据库表结构

主要表包括:
- `factor_definition`: 因子定义
- `factor_value`: 因子值
- `factor_test_report`: 因子测试报告(包含IC分析、分组回测、因子衰减分析)
- `strategy_definition`: 策略定义
- `backtest_task`: 回测任务
- `backtest_report`: 回测报告

## 性能优化建议

### MySQL配置优化 (my.cnf / my.ini)

```ini
[mysqld]
# InnoDB缓冲池大小设置为可用内存的70-80%
innodb_buffer_pool_size = 2G

# 日志文件大小
innodb_log_file_size = 512M

# 并发线程数
innodb_thread_concurrency = 0

# 查询缓存(仅MySQL 5.7及以下)
query_cache_type = 1
query_cache_size = 256M

# 连接数
max_connections = 200
```

### JPA查询优化

在`application.yml`中添加:

```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 50
          order_inserts: true
          order_updates: true
        cache:
          use_second_level_cache: true
          use_query_cache: true
```

## 数据迁移

从H2迁移到MySQL:

1. 导出H2数据
2. 在MySQL中创建数据库和表结构
3. 导入数据到MySQL
4. 修改配置文件使用MySQL profile启动

## 注意事项

1. **密码安全**: 生产环境请使用强密码,避免硬编码在配置文件中,建议使用环境变量或配置中心
2. **备份**: 定期备份数据库
3. **索引**: 根据实际查询场景优化索引
4. **字符集**: 确保使用utf8mb4字符集以支持emoji等特殊字符
5. **时区**: 确保应用时区与数据库时区一致
