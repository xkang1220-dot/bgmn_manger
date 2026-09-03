# KK 公司管理系统

技术栈对齐 `ai_customer_service_system`：Java 21、Spring Boot 3.5、MyBatis-Plus、Sa-Token、Vue 3、Vite、Element Plus。

## 模块

- 进出账 / 资金池 / 个人钱包
- 项目与任务（参与人分层 + 百分比分成，可按分成结算到钱包）
- 人员档案（绑定系统账号，自动开钱包）
- 按部门角色的权限与数据范围
- 账号管理
- 文件管理

## 启动

1. 创建数据库并导入 SQL：

```sql
source sql/init.sql
```

默认库名 `kk_manager`，账号密码见 `kk-starter/src/main/resources/application-dev.yml`（默认 `root / root`）。

2. 启动后端：

```bash
mvn -pl kk-starter -am spring-boot:run
```

3. 启动前端：

```bash
cd kk-admin
npm install
npm run dev
```

浏览器打开 http://127.0.0.1:5173

默认账号：`admin` / `admin123`
