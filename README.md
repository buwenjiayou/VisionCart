# VisionCart

AI 智能比价购物助手 — Android + Spring Boot

用户拍摄或上传商品图片，系统通过视觉模型识别商品属性，再从拼多多、淘宝联盟、eBay 等平台检索商品，完成跨平台比价、筛选、收藏和历史记录。

## 功能

- **图片识别**：识别商品类目、品牌、颜色、款式、材质等结构化属性
- **多平台检索**：对接拼多多、淘宝联盟、eBay 等平台 API
- **比价筛选**：支持价格区间、平台、评分、销量、关键词筛选
- **用户系统**：邮箱验证码登录，JWT 鉴权
- **数据持久化**：MySQL 存储用户、识别历史、收藏
- **缓存**：Redis 缓存验证码

## 技术栈

| 端 | 技术 |
|---|---|
| Android | Kotlin、Jetpack Compose、CameraX、Room、DataStore、Retrofit |
| 后端 | Spring Boot、Spring Security、Spring Data JPA、Redis、Mail |
| AI | 火山引擎 Ark（豆包视觉模型 + LLM） |
| 数据库 | MySQL 8.4 |
| 缓存 | Redis 7.x |

## 目录结构

```text
android/        Android 客户端
backend/        Spring Boot API 服务
deploy/         Docker Compose 和部署配置
docs/           架构、接口、验收等文档
gradle/         Gradle Wrapper
scripts/        辅助脚本
```

## 快速开始

### 环境要求

| 软件 | 版本 |
|---|---|
| JDK | 17 |
| Gradle | 8.x（或使用项目 wrapper） |
| Android Studio | 最新稳定版 |
| MySQL | 8.4 |
| Redis | 7.x |

### 1. 配置环境变量

```bash
cp .env.example .env
# 编辑 .env 填入真实配置
```

关键变量：

| 变量 | 说明 |
|---|---|
| `MYSQL_*` | MySQL 连接配置 |
| `REDIS_*` | Redis 连接配置 |
| `ARK_API_KEY` / `ARK_VISION_API_KEY` | 火山引擎模型密钥 |
| `PDD_*` / `TAOBAO_*` / `EBAY_*` | 电商平台 API 密钥 |
| `MAIL_*` | 邮箱验证码 SMTP 配置 |

### 2. 启动后端

```bash
# 构建
./gradlew :backend:bootJar --no-daemon

# 运行
java -jar backend/build/libs/backend-0.1.0.jar
```

健康检查：`http://localhost:8080/api/v1/health`

Swagger：`http://localhost:8080/swagger-ui.html`

### 3. 运行 Android App

1. Android Studio 打开项目根目录
2. Gradle 同步完成后运行 `android:app`
3. 模拟器访问后端用 `10.0.2.2:8080`，真机用电脑局域网 IP

## 文档

- [架构设计](docs/ARCHITECTURE.md)
- [后端 API](docs/API.md)
- [商品平台集成](docs/COMMERCE_API_INTEGRATION.md)
- [模型服务配置](docs/MODEL_PROVIDER_SETUP.md)
- [验收用例](docs/ACCEPTANCE_CASES.md)
- [部署指南](deploy/DEPLOYMENT.md)

## 安全规则

- `.env`、私密文档、APK、数据库文件不提交到仓库
- 所有平台密钥只放在后端环境变量中
- 密钥暴露后立即到对应平台重置
