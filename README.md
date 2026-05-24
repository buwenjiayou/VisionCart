# VisionCart

VisionCart 是一个 Android + Spring Boot 的 AI 智能比价购物助手。用户上传或拍摄商品图片后，系统会调用视觉模型识别商品属性，再通过拼多多、多多进宝、淘宝联盟、eBay 等真实平台接口检索商品，完成跨平台比价、筛选、收藏和历史记录。

本仓库按完整工程项目组织，包含 Android 客户端、后端 API 服务、本地基础设施脚本、部署文档、接口文档和验收文档。

## 功能概览

- 图片识别：识别商品类目、品牌、颜色、款式、材质等结构化属性。
- 多平台检索：对接拼多多、淘宝联盟、eBay 等平台 API。
- 比价筛选：支持价格区间、平台、评分、销量、关键词和自然语言筛选。
- 登录认证：邮箱验证码登录，JWT 鉴权。
- 用户数据：MySQL 保存用户、识别历史、收藏、反馈和价格提醒。
- 缓存能力：Redis 保存验证码和 NLP 语义缓存。
- Android App：Kotlin、Jetpack Compose、CameraX、Room、DataStore、Retrofit、OkHttp。
- 后端服务：Spring Boot、Spring Security、Spring Data JPA、Redis、Mail、OpenAPI、Spring AI。

## 目录结构

```text
android/      Android Kotlin + Jetpack Compose 客户端
backend/      Spring Boot API 服务
deploy/       部署说明和 Docker Compose 示例
docs/         架构、接口、AI、商品平台、验收等文档
gradle/       Gradle Wrapper 配置
web/          静态 Web 控制台和项目入口
```

## 需要安装的软件

| 软件 | 推荐版本 | 用途 |
|---|---:|---|
| Git | 最新稳定版 | 克隆、版本管理、推送仓库 |
| Microsoft OpenJDK | 17 | 构建和运行 Spring Boot 后端 |
| Gradle | 8.11.1 或兼容版本 | 构建后端和 Android 模块；本仓库的脚本在未包含 wrapper jar 时会使用系统 Gradle |
| Android Studio | 最新稳定版 | Android SDK、模拟器、真机调试、APK 构建 |
| Android SDK Platform Tools | Android Studio 安装 | 提供 `adb` |
| MySQL Server | 8.4 | 业务数据库 |
| Redis | 7.x 兼容服务 | 验证码和缓存 |
| PowerShell | 5+ 或 7+ | 运行 Windows 辅助脚本 |
| Docker Desktop | 可选 | 用容器运行 MySQL/Redis |

本机测试过的路径：

- JDK：`C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot`
- MySQL：`C:\Program Files\MySQL\MySQL Server 8.4`
- Android SDK：`C:\Users\<你的用户名>\AppData\Local\Android\Sdk`
- Redis 服务名：`Redis`

## 新手安装流程

### 1. 安装 Git

1. 打开 https://git-scm.com/download/win
2. 下载并安装 Git for Windows。
3. 验证安装：

```powershell
git --version
```

### 2. 安装 JDK 17

使用 WinGet 安装：

```powershell
winget install Microsoft.OpenJDK.17
```

验证：

```powershell
java -version
javac -version
```

如果命令找不到 Java，临时设置：

```powershell
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

### 3. 安装 Gradle

```powershell
winget install Gradle.Gradle
gradle --version
```

当前仓库提交的是轻量 Gradle 启动脚本，没有提交 `gradle-wrapper.jar`。因此新机器需要先安装系统 Gradle；如果团队希望后续完全依赖 Gradle Wrapper，可以在可信环境中重新生成 wrapper 文件后再提交。

### 4. 安装 Android Studio 和 Android SDK

1. 打开 https://developer.android.com/studio
2. 安装 Android Studio。
3. 打开 `Settings > Languages & Frameworks > Android SDK`。
4. 安装 Android SDK Platform 和 Android SDK Platform-Tools。
5. 验证 ADB：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" version
```

真机调试需要在手机中开启开发者模式和 USB 调试：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices -l
```

### 5. 安装 MySQL 8.4

```powershell
winget install Oracle.MySQL
```

创建数据库和账号：

```sql
CREATE DATABASE visioncart CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'visioncart'@'localhost' IDENTIFIED BY 'visioncart';
GRANT ALL PRIVILEGES ON visioncart.* TO 'visioncart'@'localhost';
FLUSH PRIVILEGES;
```

### 6. 安装 Redis

Windows 可以使用 Memurai、Redis on WSL 或已有的 Redis Windows 服务。项目脚本会尝试启动服务名为 `Redis` 的服务。

验证端口：

```powershell
Get-NetTCPConnection -LocalPort 6379 -ErrorAction SilentlyContinue
```

### 7. 可选：使用 Docker 运行 MySQL 和 Redis

```powershell
docker compose -f deploy/docker-compose.yml up -d
```

## 配置环境变量

真实账号和密钥不要提交到仓库。公开仓库只保留 `.env.example`。

1. 复制配置模板：

```powershell
Copy-Item .env.example .env
```

2. 按你的真实账号填写 `.env`。

关键变量：

| 变量 | 说明 |
|---|---|
| `MYSQL_URL`, `MYSQL_USERNAME`, `MYSQL_PASSWORD` | MySQL 连接 |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | Redis 连接 |
| `JWT_SECRET` | JWT 签名密钥 |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD` | 邮箱验证码 SMTP |
| `ARK_API_KEY`, `ARK_LLM_MODEL` | LLM 服务密钥和模型/端点 |
| `ARK_VISION_API_KEY`, `ARK_VISION_MODEL` | 视觉模型密钥和模型/端点 |
| `PDD_CLIENT_ID`, `PDD_CLIENT_SECRET`, `PDD_PID` | 拼多多 / 多多进宝 |
| `TAOBAO_APP_KEY`, `TAOBAO_APP_SECRET`, `TAOBAO_ADZONE_ID` | 淘宝联盟 |
| `EBAY_APP_ID`, `EBAY_CERT_ID`, `EBAY_DEV_ID` | eBay API |

本机私密复现信息放在 `PRIVATE_REPRODUCTION_NOTES.md`，该文件已加入 `.gitignore`，不要上传。

## 本地运行

### 启动基础服务

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\setup_env.ps1
.\start_services.ps1
```

### 构建后端

```powershell
.\gradlew.bat :backend:bootJar --no-daemon
```

### 启动后端

```powershell
.\start_backend.ps1 -Port 8080
```

健康检查：

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/health
```

Swagger：

```text
http://localhost:8080/swagger-ui.html
```

## 运行 Android App

1. 用 Android Studio 打开项目根目录。
2. 等待 Gradle 同步完成。
3. 创建或修改 `local.properties`：

```properties
sdk.dir=C\:\\Users\\<你的用户名>\\AppData\\Local\\Android\\Sdk
VISIONCART_API_BASE_URL=http://10.0.2.2:8080/
```

模拟器访问电脑本机后端使用 `10.0.2.2`。真机访问电脑后端时，改成电脑局域网 IP：

```properties
VISIONCART_API_BASE_URL=http://你的电脑局域网IP:8080/
```

4. 在 Android Studio 中运行 `android:app`，或命令行构建：

```powershell
.\gradlew.bat :android:app:assembleDebug --no-daemon
```

5. 安装到真机：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r android\app\build\outputs\apk\debug\app-debug.apk
```

## 常用命令

```powershell
# 后端健康检查
Invoke-RestMethod http://localhost:8080/api/v1/health

# 构建后端 jar
.\gradlew.bat :backend:bootJar --no-daemon

# 构建 Android Debug APK
.\gradlew.bat :android:app:assembleDebug --no-daemon

# 查看 Android 设备
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices -l

# 查看 Android 日志
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" logcat -d -t 500
```

## 文档

- [架构设计](docs/ARCHITECTURE.md)
- [后端 API](docs/API.md)
- [商品平台 API 集成](docs/COMMERCE_API_INTEGRATION.md)
- [模型服务配置](docs/MODEL_PROVIDER_SETUP.md)
- [AI 使用总结](docs/AI_USAGE.md)
- [验收用例](docs/ACCEPTANCE_CASES.md)
- [端到端验收指南](docs/VALIDATION_GUIDE.md)
- [部署指南](deploy/DEPLOYMENT.md)

## 安全规则

- 不提交 `.env`、本地截图、日志、APK、数据库文件、签名证书和私密复现文档。
- 不把平台密钥写进 Android 或 Web 代码。
- 所有模型和商品平台密钥只放在后端环境变量中。
- 如果密钥曾经暴露到公开历史中，应立即到对应平台重置。

## GitHub 仓库

目标仓库：

```text
https://github.com/buwenjiayou/VisionCart.git
```

首次推送：

```powershell
git remote add origin https://github.com/buwenjiayou/VisionCart.git
git branch -M main
git push -u origin main
```
