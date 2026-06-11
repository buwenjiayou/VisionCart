# VisionCart

VisionCart 是一个 AI 智能比价购物助手，包含 Android 客户端和 Spring Boot 后端。用户拍摄或上传商品图片后，后端通过视觉模型识别商品属性，再从拼多多、淘宝联盟、eBay 等平台检索商品，完成跨平台比价、筛选、收藏、历史记录和价格提醒。

## 项目结构

```text
VisionCart/
├── android/app/                 Android Kotlin + Jetpack Compose 客户端
├── backend/                     Spring Boot 后端服务
├── deploy/docker-compose.yml    后端 + MySQL + Redis + Prometheus + Grafana
├── deploy/local-monitoring/     仅启动本地 Prometheus + Grafana
├── docs/                        架构、监控、验收、平台 API 文档
├── gradle/                      根项目 Gradle Wrapper
└── scripts/                     辅助脚本
```

## 依赖安装

### 必需软件

| 依赖 | 要求 | 说明 |
| --- | --- | --- |
| JDK | 17 | 后端构建和 Android 编译都使用 Java 17 |
| Android Studio | 稳定版 | 需要 Android SDK，项目 `compileSdk` 为 36，`minSdk` 为 26 |
| Docker Desktop | 可选 | 推荐用于一键启动 MySQL、Redis、后端和监控组件 |
| MySQL | 8.4 | 不使用 Docker 启动后端时需要手动安装 |
| Redis | 7.x | 不使用 Docker 启动后端时需要手动安装 |
| adb | 可选 | 命令行安装 APK 到真机或模拟器时使用 |

项目已提交 Gradle Wrapper，不需要单独安装 Gradle。首次构建时会自动下载 Spring Boot、Android、Kotlin、Compose、Room、Retrofit 等依赖。

Windows PowerShell 使用：

```powershell
.\gradlew.bat :backend:bootJar --no-daemon
.\gradlew.bat :android:app:assembleDebug --no-daemon
```

macOS / Linux / Git Bash 使用：

```bash
./gradlew :backend:bootJar --no-daemon
./gradlew :android:app:assembleDebug --no-daemon
```

安装完软件后，请删除安装包或压缩包，避免 `.exe`、`.msi`、`.zip`、`.tar.gz` 等文件残留占用磁盘空间。

## 环境配置

### 1. 后端 `.env`

从示例文件复制本地配置：

```powershell
Copy-Item .env.example .env
```

macOS / Linux：

```bash
cp .env.example .env
```

`.env` 不提交到仓库。启动后端前至少确认这些变量：

| 变量 | 说明 |
| --- | --- |
| `PORT` / `HOST_PORT` | 后端容器内端口和宿主机映射端口，默认 `8080` |
| `PUBLIC_BASE_URL` | 后端对外访问地址，例如 `http://localhost:8080` |
| `JWT_SECRET` / `JWT_EXPIRATION` | JWT 签名密钥和有效期，生产环境必须更换 |
| `MYSQL_URL` | 本地启动后端时使用的 MySQL JDBC 地址 |
| `DOCKER_MYSQL_URL` | Docker Compose 使用外部 MySQL 时填写；为空则使用 compose 内置 `mysql` 服务 |
| `MYSQL_USERNAME` / `MYSQL_PASSWORD` / `MYSQL_ROOT_PASSWORD` | MySQL 用户和密码 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | Redis 连接配置 |
| `ARK_BASE_URL` / `ARK_API_KEY` / `ARK_LLM_MODEL` | 火山引擎 Ark / Spring AI LLM 配置 |
| `ARK_VISION_API_KEY` / `ARK_VISION_MODEL` | 豆包视觉识别模型配置 |
| `ALIYUN_BAILIAN_API_KEY` / `ALIYUN_BAILIAN_BASE_URL` | 阿里云百炼 Qwen-VL 两阶段识别配置 |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` | 邮箱验证码 SMTP 配置，密码应使用 SMTP 授权码 |
| `PDD_CLIENT_ID` / `PDD_CLIENT_SECRET` / `PDD_PID` | 拼多多 / 多多进宝 API 配置 |
| `TAOBAO_APP_KEY` / `TAOBAO_APP_SECRET` / `TAOBAO_ADZONE_ID` | 淘宝联盟 API 配置 |
| `EBAY_APP_ID` / `EBAY_CERT_ID` / `EBAY_DEV_ID` | eBay API 配置 |
| `VISIONCART_ALLOWED_ORIGINS` | Web/CORS 允许来源，Android 原生请求不受 CORS 限制 |
| `VISIONCART_MONITOR_TOKEN` | 监控接口访问令牌，配合 Prometheus 使用 |

注意：后端不会自动读取 `.env` 文件。直接运行 jar 前，需要先把 `.env` 加载为进程环境变量。Windows 本地联调可以使用仓库内的 `run-backend.ps1`。

### 2. MySQL 和 Redis

如果使用 Docker Compose，可以跳过手动安装和建库。Compose 会启动 MySQL 8.4 与 Redis 7，并自动通过 Flyway 初始化表结构。

如果本机手动启动后端，请先启动 MySQL 和 Redis，并创建数据库：

```sql
CREATE DATABASE visioncart CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'visioncart'@'%' IDENTIFIED BY 'change-me';
GRANT ALL PRIVILEGES ON visioncart.* TO 'visioncart'@'%';
FLUSH PRIVILEGES;
```

然后让 `.env` 中的 `MYSQL_URL`、`MYSQL_USERNAME`、`MYSQL_PASSWORD` 与实际数据库一致。

### 3. Android `local.properties`

Android 构建从根目录 `local.properties` 读取 SDK 路径、API 地址和 Release 签名配置。该文件不提交到仓库。

开发调试最小配置：

```properties
sdk.dir=C:/Android/Sdk
VISIONCART_API_BASE_URL=http://10.0.2.2:8080/
```

常见后端地址：

| 场景 | `VISIONCART_API_BASE_URL` |
| --- | --- |
| Android 模拟器访问本机后端 | `http://10.0.2.2:8080/` |
| 真机访问电脑后端 | `http://<电脑局域网IP>:8080/` |
| 使用 ADB 端口转发 | `http://localhost:8080/` |
| 正式发布 | 公网 HTTPS 地址 |

Release 构建默认要求 `VISIONCART_API_BASE_URL` 为公网 HTTPS，不能使用 localhost、模拟器地址或局域网地址。自用环境确需 HTTP 发布时，需要显式配置白名单：

```properties
VISIONCART_ALLOW_HTTP_RELEASE=true
VISIONCART_ALLOWED_HTTP_RELEASE_HOSTS=example.com
```

Release 签名配置示例：

```properties
VISIONCART_RELEASE_STORE_FILE=keystore/release.jks
VISIONCART_RELEASE_STORE_PASSWORD=replace-with-store-password
VISIONCART_RELEASE_KEY_ALIAS=replace-with-key-alias
VISIONCART_RELEASE_KEY_PASSWORD=replace-with-key-password
```

不要把 keystore、签名密码或真实 API 地址提交到仓库。

## 项目启动方式

### 方式一：Docker Compose 启动完整后端环境

这是推荐的后端启动方式，会同时启动 Spring Boot 后端、MySQL、Redis、Prometheus 和 Grafana。

```powershell
Copy-Item .env.example .env
# 编辑 .env 后执行
docker compose --env-file .env -f deploy/docker-compose.yml up -d --build
```

查看状态：

```powershell
docker compose --env-file .env -f deploy/docker-compose.yml ps
```

停止服务：

```powershell
docker compose --env-file .env -f deploy/docker-compose.yml down
```

访问入口：

| 服务 | 地址 |
| --- | --- |
| 后端健康检查 | `http://localhost:8080/api/v1/health` |
| Swagger，本地 profile 可用 | `http://localhost:8080/swagger-ui.html` |
| Prometheus | `http://localhost:9090` |
| Grafana | `http://localhost:3000` |

Compose 后端使用 `SPRING_PROFILES_ACTIVE=prod`，生产 profile 会关闭 Swagger UI 和 OpenAPI 文档；如果需要 Swagger，请使用本地 jar 启动方式。

### 方式二：本地 jar 启动后端

适合后端开发调试。需要本机已启动 MySQL 8.4 和 Redis 7.x。

```powershell
.\gradlew.bat :backend:bootJar --no-daemon
.\run-backend.ps1
```

macOS / Linux：

```bash
./gradlew :backend:bootJar --no-daemon
set -a
source .env
set +a
java -jar backend/build/libs/backend-0.1.0.jar
```

启动后检查：

```bash
curl http://localhost:8080/api/v1/health
```

本地 profile 下 Swagger 地址：

```text
http://localhost:8080/swagger-ui.html
```

### 方式三：只启动本地监控

如果后端已经在本机或其他环境运行，只想启动 Prometheus 和 Grafana：

```powershell
docker compose -f deploy/local-monitoring/docker-compose.yml up -d
```

配置文件位于 `deploy/local-monitoring/.env`，需要保证其中的 `VISIONCART_MONITOR_TOKEN` 与后端 `.env` 一致。

停止本地监控：

```powershell
docker compose -f deploy/local-monitoring/docker-compose.yml down
```

### 方式四：运行 Android 客户端

使用 Android Studio：

1. 打开项目根目录 `VisionCart`
2. 等待 Gradle Sync 完成
3. 确认根目录 `local.properties` 已配置 `sdk.dir` 和 `VISIONCART_API_BASE_URL`
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

构建 Release APK：

```powershell
.\gradlew.bat :android:app:assembleRelease --no-daemon
```

Release 构建前请确认 `local.properties` 中的 API 地址校验和签名配置已经满足要求。

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

# Android Release 包
.\gradlew.bat :android:app:assembleRelease --no-daemon
```

## 运行检查

后端启动成功后，至少检查：

```bash
curl http://localhost:8080/api/v1/health
```

Android 调试时如果无法连接后端，优先检查：

- `VISIONCART_API_BASE_URL` 是否以 `/` 结尾
- 模拟器是否使用 `10.0.2.2` 访问宿主机
- 真机和电脑是否在同一局域网
- Windows 防火墙是否放行后端端口
- 后端 `.env` 中 `SERVER_ADDRESS` 是否为 `0.0.0.0`

## 安全与提交规则

- 不要提交 `.env`、`local.properties`、keystore、密钥、数据库文件、APK、AAB、日志文件
- 所有平台密钥只放在后端环境变量中，不要写入 Android 代码
- 生产环境必须更换 `JWT_SECRET`、数据库密码、SMTP 授权码、监控令牌和平台 API 密钥
- Release APK 必须使用受控 keystore 签名，并确认后端地址符合发布策略
- 安装完软件后必须删除安装包或压缩包，避免安装文件残留
