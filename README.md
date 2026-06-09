# VisionCart

VisionCart 是一个 AI 智能比价购物助手，包含 Android 客户端和 Spring Boot 后端。用户拍摄或上传商品图片后，系统通过视觉模型识别商品属性，再从拼多多、淘宝联盟、eBay 等平台检索商品，完成跨平台比价、筛选、收藏和价格提醒。

## 功能概览

- 图片识别：识别商品类目、品牌、颜色、款式、材质等结构化属性
- 多平台检索：对接拼多多、淘宝联盟、eBay 等商品平台 API
- 智能比价：支持价格区间、平台、评分、销量、关键词等筛选条件
- 邮箱验证码登录：后端签发 JWT，Android 自动携带 Token
- 识别历史与收藏：MySQL 持久化，Android Room 本地缓存
- 价格监控：收藏商品后异步刷新价格并推送提醒

## 技术栈

| 模块 | 技术 |
| --- | --- |
| Android | Kotlin、Jetpack Compose、CameraX、Room、DataStore、Retrofit |
| 后端 | Spring Boot 3.5、Java 17、Spring Security、Spring Data JPA、Redis、WebSocket |
| AI | 火山引擎 Ark / 豆包视觉模型、Spring AI LLM |
| 数据库 | MySQL 8.4 |
| 缓存 | Redis 7.x |
| 构建 | Gradle Wrapper、Android Gradle Plugin 8.7.3 |

## 目录结构

```text
VisionCart/
├── android/app/     Android 客户端
├── backend/         Spring Boot 后端服务
├── deploy/          Docker Compose、Prometheus 配置
├── docs/            监控、验收、平台 API 等补充文档
├── gradle/          Gradle Wrapper
└── scripts/         辅助脚本
```

## 依赖安装

### 必需软件

| 软件 | 要求 |
| --- | --- |
| JDK | 17 |
| Android Studio | 稳定版，包含 Android SDK |
| Docker Desktop | 可选，用于一键启动 MySQL、Redis 和后端 |
| MySQL | 8.4，非 Docker 启动后端时需要 |
| Redis | 7.x，非 Docker 启动后端时需要 |

项目已提交 Gradle Wrapper，不需要单独安装 Gradle。首次构建时 Gradle 会自动下载后端和 Android 依赖。

在 Windows PowerShell 中执行 Gradle 命令：

```powershell
.\gradlew.bat :backend:bootJar --no-daemon
.\gradlew.bat :android:app:assembleDebug --no-daemon
```

在 macOS / Linux / Git Bash 中执行：

```bash
./gradlew :backend:bootJar --no-daemon
./gradlew :android:app:assembleDebug --no-daemon
```

Android SDK 路径需要写入根目录 `local.properties`：

```properties
sdk.dir=C:/Android/Sdk
```

## 环境配置

### 1. 创建后端环境变量文件

从示例文件复制一份本地配置：

```powershell
Copy-Item .env.example .env
```

macOS / Linux：

```bash
cp .env.example .env
```

`.env` 不会提交到仓库。启动后端前必须至少检查以下配置：

| 变量 | 说明 |
| --- | --- |
| `PORT` | 后端端口，默认 `8080` |
| `PUBLIC_BASE_URL` | 后端对外访问地址 |
| `JWT_SECRET` | JWT 签名密钥，生产环境必须更换 |
| `MYSQL_URL` | MySQL JDBC 地址 |
| `MYSQL_USERNAME` / `MYSQL_PASSWORD` | MySQL 用户名和密码 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | Redis 连接配置 |
| `ARK_API_KEY` / `ARK_LLM_MODEL` | NLP LLM 配置 |
| `ARK_VISION_API_KEY` / `ARK_VISION_MODEL` | 视觉识别模型配置 |
| `ALIYUN_BAILIAN_API_KEY` | Qwen-VL 两阶段识别配置 |
| `MAIL_HOST` / `MAIL_USERNAME` / `MAIL_PASSWORD` | 邮箱验证码 SMTP 配置 |
| `PDD_CLIENT_ID` / `PDD_CLIENT_SECRET` / `PDD_PID` | 拼多多 API 配置 |
| `TAOBAO_APP_KEY` / `TAOBAO_APP_SECRET` / `TAOBAO_ADZONE_ID` | 淘宝联盟 API 配置 |
| `EBAY_APP_ID` / `EBAY_CERT_ID` / `EBAY_DEV_ID` | eBay API 配置 |

后端不会自动读取 `.env` 文件，直接运行 jar 前需要先把 `.env` 加载为进程环境变量。仓库内的 `run-backend.ps1`、`start-backend.ps1`、`start-backend.sh` 已包含加载逻辑。

### 2. 准备 MySQL 和 Redis

如果不用 Docker，请手动创建数据库并启动 Redis：

```sql
CREATE DATABASE visioncart CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'visioncart'@'%' IDENTIFIED BY 'change-me';
GRANT ALL PRIVILEGES ON visioncart.* TO 'visioncart'@'%';
FLUSH PRIVILEGES;
```

然后确保 `.env` 中的 `MYSQL_URL`、`MYSQL_USERNAME`、`MYSQL_PASSWORD` 与实际数据库一致。数据库表结构由 Flyway 在后端启动时自动迁移。

### 3. 配置 Android 后端地址

在根目录 `local.properties` 中添加：

```properties
VISIONCART_API_BASE_URL=http://10.0.2.2:8080/
```

常见取值：

| 场景 | 地址 |
| --- | --- |
| Android 模拟器访问本机后端 | `http://10.0.2.2:8080/` |
| 真机访问电脑后端 | `http://<电脑局域网IP>:8080/` |
| ADB 端口转发后访问 | `http://localhost:8080/` |
| 发布版 | 必须使用公网 HTTPS 地址 |

发布构建会校验 `VISIONCART_API_BASE_URL`，不能使用 localhost、模拟器地址或局域网地址。

## 项目启动方式

### 方式一：本地启动后端

1. 启动 MySQL 8.4 和 Redis 7.x。
2. 配好 `.env`。
3. 构建后端 jar：

```powershell
.\gradlew.bat :backend:bootJar --no-daemon
```

4. 启动后端：

```powershell
.\run-backend.ps1
```

macOS / Linux：

```bash
./gradlew :backend:bootJar --no-daemon
./start-backend.sh
```

健康检查：

```bash
curl http://localhost:8080/api/v1/health
```

Swagger 文档：

```text
http://localhost:8080/swagger-ui.html
```

### 方式二：Docker Compose 启动全套后端依赖

Docker Compose 会启动后端、MySQL、Redis、Prometheus 和 Grafana：

```powershell
docker compose --env-file .env -f deploy/docker-compose.yml up -d --build
```

查看服务状态：

```powershell
docker compose --env-file .env -f deploy/docker-compose.yml ps
```

停止服务：

```powershell
docker compose --env-file .env -f deploy/docker-compose.yml down
```

访问地址：

| 服务 | 地址 |
| --- | --- |
| 后端健康检查 | `http://localhost:8080/api/v1/health` |
| Prometheus | `http://localhost:9090` |
| Grafana | `http://localhost:3000` |

### 方式三：启动 Android 客户端

使用 Android Studio：

1. 打开项目根目录 `VisionCart`
2. 等待 Gradle Sync 完成
3. 确认根目录 `local.properties` 包含 `sdk.dir` 和 `VISIONCART_API_BASE_URL`
4. 选择 `android:app` 运行到模拟器或真机

命令行构建 Debug APK：

```powershell
.\gradlew.bat :android:app:assembleDebug --no-daemon
```

APK 输出路径：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接设备：

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

## 常用命令

```powershell
# 后端测试
.\gradlew.bat :backend:test --no-daemon

# Android 单元测试
.\gradlew.bat :android:app:testDebugUnitTest --no-daemon

# 后端打包
.\gradlew.bat :backend:bootJar --no-daemon

# Android Debug 包
.\gradlew.bat :android:app:assembleDebug --no-daemon
```

## 安全与提交规则

- 不要提交 `.env`、`local.properties`、密钥、数据库文件、APK、AAB、日志文件
- 所有平台密钥只放在后端环境变量中，不要写入 Android 代码
- 生产环境必须更换 `JWT_SECRET`、数据库密码、SMTP 授权码和平台 API 密钥
- 安装软件后请删除安装包或压缩包，避免 `.exe`、`.msi`、`.zip`、`.tar.gz` 等文件残留

## 相关文档

- [监控配置](docs/monitoring-setup-guide.md)
- [性能指标](docs/performance-metrics-guide.md)
- [最小功能验收](docs/minimal-functional-validation.md)
- [拼多多 DDK API 参考](docs/pdd-ddk-api-reference.md)
- [淘宝 TBK API 参考](docs/taobao-tbk-api-reference.md)
