# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Rules

- 安装完软件后，必须删除安装包或压缩包（.exe、.msi、.zip、.tar.gz 等），不要残留安装文件占用磁盘空间。

## Project Overview

VisionCart 是一个 AI 智能比价购物助手，包含 Android 客户端和 Spring Boot 后端。用户拍摄商品图片，系统通过视觉模型识别属性，再从拼多多、淘宝联盟、eBay 等平台检索商品完成比价。

## Build & Run Commands

```bash
# 构建后端 jar
./gradlew :backend:bootJar --no-daemon

# 运行后端（需要 MySQL + Redis 已启动）
java -jar backend/build/libs/backend-0.1.0.jar

# 构建 Android Debug APK
./gradlew :android:app:assembleDebug --no-daemon

# 安装到真机
adb install -r android/app/build/outputs/apk/debug/app-debug.apk

# 后端健康检查
curl http://localhost:8080/api/v1/health

# Swagger API 文档
open http://localhost:8080/swagger-ui.html
```

## Architecture

### Gradle Multi-Module

```
VisionCart/              # Root project (settings.gradle)
├── backend/             # Spring Boot 3.5 + Java 17
└── android/app/         # Android Kotlin + Jetpack Compose (minSdk 26, targetSdk 36)
```

Root `build.gradle` declares shared plugin versions. Each module has its own `build.gradle`.

### Backend Structure (backend/src/main/java/com/visioncart/)

```
api/controller/    REST controllers (Auth, Recognition, Search, Nlp, Suggestion, UserData, PriceAlert, Health, Metrics)
api/dto/           Request/Response DTOs (records)
config/            Spring config (Security, JWT, WebSocket, Async, OpenAPI, VisionCartProperties, PlatformConfigProperties)
domain/            JPA entities (User, RecognitionHistory, FavoriteProduct, PriceAlert, PriceHistory, RecognitionFeedback)
repository/        Spring Data JPA repositories
service/
├── ai/            AI infrastructure (PromptLoader, RetryPolicy, AiTraceService, HashUtils)
├── auth/          AuthService (email verification code + JWT)
├── metrics/       Performance metrics collection (PerformanceMetricsService)
├── nlp/           NLP parsing (RuleBasedNlpParser + SpringAiNlpService with LLM fallback)
├── price/         PriceMonitorService + PriceAlertScheduler
├── recognition/   RecognitionOrchestrator, VisionModelService (Doubao Vision), ImageProcessor
├── search/        SearchOrchestrator, PlatformSearchService (PDD/Taobao/eBay), CircuitBreaker, RegionResolver
└── suggestion/    SuggestionService (smart recommendation cards with undo)
```

### Key Backend Patterns

- **异步识别**: 识别请求提交后返回 sessionId，客户端轮询 `/api/v1/recognition/status/{sessionId}` 或通过 WebSocket `/topic/recognition/{sessionId}` 获取结果
- **多平台搜索**: SearchOrchestrator 并行调用各平台 PlatformSearchService，通过 PlatformCircuitBreaker 做熔断保护
- **平台能力配置表**: 通过 `visioncart.platforms.*` 配置各平台的开关、超时、权重、降级策略、区域策略，支持环境变量覆盖
- **统一性能指标**: 基于 Micrometer 收集识别延迟、视觉模型延迟、搜索延迟、NLP 延迟、缓存命中率、熔断器状态等指标，通过 `/api/v1/metrics/*` 和 Actuator 暴露
- **NLP 双引擎**: RuleBasedNlpParser 规则优先，不完整时调 LLM 补全，LLM 不可用时降级到规则+历史
- **价格监控**: 收藏商品时异步刷新价格，三种提醒（目标价格、历史低价30天、大幅降价15%+），WebSocket 推送
- **环境变量**: 所有敏感配置通过 `.env` 文件注入，后端通过 `application.yml` 的 `${}` 语法读取

### Android Structure (android/app/src/main/java/com/visioncart/app/)

```
data/
├── ApiClient.kt         Retrofit API interface + OkHttp interceptor
├── Models.kt            Data classes (ProductCard, SearchFilter, RecognitionResult, etc.)
├── AuthModels.kt        Login/SendCode request/response
├── TokenManager.kt      DataStore token persistence
├── db/                  Room database (AppDatabase, FavoriteProductDao, RecognitionRecordDao)
└── repository/          VisionCartRepository (single source of truth, local + remote sync)

ui/
├── auth/LoginScreen.kt          邮箱验证码登录
├── camera/CameraScreen.kt       CameraX 拍照
├── components/VisionCartComponents.kt  共享 UI 组件 (ProductCardView, RecognitionPanel, NlpInputBar, etc.)
├── detail/ProductDetailScreen.kt      WebView 商品详情
├── favorites/FavoritesScreen.kt       收藏列表
├── history/HistoryScreen.kt           识别历史
└── viewmodel/MainViewModel.kt         主 ViewModel (识别、搜索、NLP、收藏、推荐)

overlay/FloatingWindowService.kt   悬浮窗服务 (悬浮球、菜单、截图识别、悬浮面板)
```

### Key Android Patterns

- **API Base URL**: 在 `android/app/build.gradle` 中通过 `local.properties` 的 `VISIONCART_API_BASE_URL` 注入 BuildConfig。模拟器用 `10.0.2.2:8080`，真机用电脑局域网 IP，ADB 转发用 `localhost:8080`
- **认证**: TokenManager (DataStore) 持久化 token，OkHttp Interceptor 自动携带 JWT
- **本地缓存**: Room 数据库存储收藏和识别历史，与后端同步
- **悬浮窗**: FloatingWindowService 实现悬浮球 → 菜单(截图/拍照) → 悬浮面板完整流程，通过 MediaProjection 截屏

## Environment Setup

1. 复制 `.env.example` 为 `.env`，填入真实配置
2. 启动 MySQL 8.4 + Redis 7.x
3. 后端需要 JDK 17，Android 需要 Android Studio

## Monitoring & Metrics

### Performance Metrics

VisionCart 收集以下关键性能指标：

| 指标类别 | 指标名称 | 说明 |
|---------|---------|------|
| 识别 | `recognition.latency` | 识别总延迟 |
| 识别 | `vision_model.latency` | 视觉模型调用延迟 |
| 搜索 | `search.total_latency` | 搜索总延迟 |
| 搜索 | `search.platform_latency` | 各平台搜索延迟 |
| 搜索 | `search.cache_hit_rate` | 搜索缓存命中率 |
| NLP | `nlp.parse_latency` | NLP 解析延迟 |
| NLP | `nlp.llm_fallback_rate` | NLP LLM 回退率 |
| 候选 | `candidate.filter_latency` | 候选过滤延迟 |
| 平台 | `platform.circuit_open_count` | 熔断器打开次数 |

### Metrics API

```bash
# 获取所有指标摘要
curl http://localhost:8080/api/v1/metrics/summary

# 获取搜索指标
curl http://localhost:8080/api/v1/metrics/search

# 获取 NLP 指标
curl http://localhost:8080/api/v1/metrics/nlp

# 获取平台指标
curl http://localhost:8080/api/v1/metrics/platforms

# 打印指标到服务器日志
curl http://localhost:8080/api/v1/metrics/log

# Prometheus 格式（需要 Actuator）
curl http://localhost:8080/actuator/prometheus
```

### Platform Configuration

平台能力配置表支持以下配置项：

```yaml
visioncart:
  platforms:
    pdd:
      enabled: true
      timeout-ms: 2500
      weight: 1.0
      fallback-enabled: true
      fallback-priority: 1
      region-strategy: domestic
      max-retries: 2
      retry-delay-ms: 500
      rate-limit-per-second: 10
      circuit-breaker-enabled: true
```

详细配置说明见 `docs/performance-metrics-guide.md`。

## Conventions

- 后端 JSON 命名策略: `SNAKE_CASE`（Jackson 配置）
- 后端返回格式: `ApiResponse<T>` 包装，code 200 表示成功
- Android Compose 组件使用 Material 3
- 项目主色调: `Color(0xFF0A7C66)` (绿色)
- 敏感信息只放后端环境变量，不放 Android 代码
