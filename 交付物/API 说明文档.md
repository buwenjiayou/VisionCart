# VisionCart API 说明文档

> 交付物类型：服务端 API 详细说明  
> 项目：VisionCart AI 智能比价购物助手  
> 后端版本：0.1.0  
> 后端技术栈：Spring Boot 3.5、Java 17、Spring Security、JWT、Springdoc OpenAPI、WebSocket/STOMP  
> 默认服务地址：`http://localhost:8080`

## 1. Swagger / OpenAPI 规范

VisionCart 后端已接入 Springdoc OpenAPI：

| 能力 | 地址 | 说明 |
| --- | --- | --- |
| Swagger UI | `GET /swagger-ui.html` | 在线接口查看与调试页面 |
| OpenAPI JSON | `GET /v3/api-docs` | OpenAPI 3.0 JSON 规范描述 |
| 健康检查 | `GET /api/v1/health` | 无需登录的基础服务探活 |

相关配置：

- 依赖：`org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.9`
- 开发环境 Swagger UI 路径：`springdoc.swagger-ui.path=/swagger-ui.html`
- 生产环境 `application-prod.yml` 关闭 `springdoc.api-docs` 与 `springdoc.swagger-ui`
- `OpenApiConfig` 当前声明 API 标题、版本、描述与 License，未显式声明 `bearerAuth` 安全方案；如 Swagger 页面没有 `Authorize` 按钮，可使用 Apifox、Postman、curl 等工具手动添加 `Authorization` 请求头

OpenAPI 元信息：

| 字段 | 值 |
| --- | --- |
| title | `VisionCart API` |
| version | `0.1.0` |
| description | `AI 拍照识物与智能比价购物助手 API` |
| license | `Proprietary` |

## 2. 通用约定

### 2.1 统一响应

除图片资源等二进制接口外，REST API 统一返回 `ApiResponse<T>`：

```json
{
  "code": 200,
  "message": "ok",
  "data": {},
  "trace_id": null
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | integer | 业务状态码，`200` 表示成功 |
| `message` | string | 响应提示，成功默认为 `ok` |
| `data` | object / array / null | 业务数据 |
| `trace_id` | string / null | 链路追踪 ID，当前通常为空 |

后端 Jackson 使用 `SNAKE_CASE` 命名策略，因此 Java 字段会自动转为下划线命名，例如：

| Java 字段 | JSON 字段 |
| --- | --- |
| `sessionId` | `session_id` |
| `pageSize` | `page_size` |
| `imageUrl` | `image_url` |
| `currentFilter` | `current_filter` |

### 2.2 鉴权

业务接口使用 JWT 鉴权，请求头格式：

```http
Authorization: Bearer <access_token>
```

公开接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/v1/auth/send-code` | 发送邮箱验证码 |
| `POST` | `/api/v1/auth/login` | 邮箱验证码登录 |
| `POST` | `/api/v1/auth/refresh` | 刷新 Token |
| `GET` | `/api/v1/health` | 基础健康检查 |
| `GET` | `/actuator/health` | Actuator 健康检查 |
| `GET` | `/ws/recognition` | WebSocket 握手入口，消息层校验 JWT |

其他 `/api/v1/**` 接口默认需要登录。Swagger UI、OpenAPI JSON、指标接口均需要鉴权；生产环境默认关闭 Swagger。

管理员接口：

| 路径 | 说明 |
| --- | --- |
| `/api/v1/metrics/**` | 性能指标 |
| `/api/v1/recognition/feedback/stats` | 属性修正统计 |
| `/actuator/metrics/**` | Actuator 指标 |
| `/actuator/prometheus` | Prometheus 指标 |

管理员 ID 通过 `VISIONCART_ADMIN_USER_IDS` 配置。Prometheus 可通过 `VISIONCART_MONITOR_TOKEN` 使用 Bearer Token 访问 `/actuator/prometheus`。

### 2.3 常见错误码

| HTTP / 业务码 | 说明 |
| --- | --- |
| `400` | 参数错误、JSON 格式错误、图片不符合要求 |
| `401` | 未登录、Token 无效或过期 |
| `403` | 无权访问指定用户资源或管理员权限不足 |
| `404` | 任务、历史记录、图片或商品不存在 |
| `405` | 请求方法不支持 |
| `415` | 媒体类型不支持 |
| `429` | 请求过于频繁，触发限流 |
| `500` | 服务端内部错误 |
| `503` | Redis、数据库或线程池暂不可用 |
| `504` | 数据库查询超时 |

### 2.4 限流

后端启用 `RateLimitFilter`：

| 接口类型 | 默认配置 |
| --- | --- |
| 默认 API | `RATE_LIMIT_DEFAULT_LIMIT=120` / `RATE_LIMIT_DEFAULT_WINDOW_SECONDS=60` |
| 发送验证码 | `RATE_LIMIT_AUTH_LIMIT=10` / `RATE_LIMIT_AUTH_WINDOW_SECONDS=300` |
| 登录 | 使用 auth 窗口，次数上限不超过 20 |
| 图片识别 | `RATE_LIMIT_RECOGNITION_LIMIT=20` / `RATE_LIMIT_RECOGNITION_WINDOW_SECONDS=3600` |
| NLP 解析 | 与识别限流配置一致 |

## 3. 核心数据模型

### 3.1 SearchFilter

用于商品搜索、NLP 筛选、推荐动作和统一动作结果。

```json
{
  "price_range": { "min": 100, "max": 500 },
  "platforms": ["pdd", "taobao", "ebay"],
  "self_operated": true,
  "colors": ["black"],
  "brands": ["Apple"],
  "rating_min": 4.5,
  "sort_by": "price",
  "sort_order": "asc",
  "keyword": "手机壳",
  "attributes": {
    "material": "硅胶"
  },
  "exclude_roles": ["accessory"],
  "capabilities": {
    "airplane_allowed": true,
    "fast_charging": true
  }
}
```

### 3.2 ProductCard

商品卡片模型，用于搜索结果、筛选结果、历史快照、收藏和推荐动作。

| 字段 | 说明 |
| --- | --- |
| `id` | 平台商品 ID |
| `title` | 商品标题 |
| `image_url` | 商品图片 |
| `price` / `original_price` | 当前价 / 原价 |
| `platform` | 平台：`pdd`、`taobao`、`ebay` 等 |
| `self_operated` | 是否自营 |
| `shop_name` | 店铺名称 |
| `rating` | 商品评分 |
| `sales` / `sales_label` | 销量数值 / 展示文案 |
| `similarity` | 与识别或搜索意图的匹配分 |
| `tags` | 商品标签 |
| `detail_url` | 商品详情 URL |
| `brand` | 品牌 |
| `main_category_code` | 主类目代码 |
| `product_role` | 商品角色，例如主商品、配件、赠品 |
| `rating_display_label` | 评分来源展示文案 |
| `reputation_index` | 口碑指数，0 到 100 |
| `reputation_score` | 内部口碑分，0 到 1 |
| `reputation_confidence` | 口碑置信度 |

### 3.3 ActionResult

统一动作返回模型。NLP、推荐、属性修正、标签删除、排序、撤回等新流程都尽量使用该结构。

| 字段 | 说明 |
| --- | --- |
| `products` | 当前应展示的商品列表 |
| `applied_filter` | 动作后的筛选状态 |
| `filter_tags` | 结构化筛选标签 |
| `filter_applied` | 是否真正提交筛选；`false` 表示 ZeroResultGuard 回滚 |
| `kept_previous_results` | 是否保留旧结果 |
| `can_undo` | 是否还能继续撤回 |
| `message` | 展示消息 |
| `warnings` | 风险或降级提示 |
| `explanations` | 筛选解释 |
| `ui_action` | 非筛选动作，例如打开价格提醒弹窗 |
| `total_in_pool` | 候选池总数 |
| `suggestion_cards` | 新推荐卡片 |
| `updated_attributes` | 属性修正后的属性 |
| `action_source` | 动作来源 |
| `undo_token` | 撤回标识 |
| `fallback_products` | 稀疏结果下保留的旧商品 |
| `display_mode` | `NORMAL`、`MIXED_RESULTS`、`ROLLED_BACK` |
| `message_code` | 客户端 i18n 消息码 |
| `attributes_updated` | 属性是否更新 |
| `products_updated` | 商品列表是否刷新 |

## 4. API 总览

| 模块 | 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- | --- |
| 健康检查 | `GET` | `/api/v1/health` | 公开 | 基础存活检查 |
| 健康检查 | `GET` | `/api/v1/health/deep` | 登录 | MySQL、Redis 深度检查 |
| 认证 | `POST` | `/api/v1/auth/send-code` | 公开 | 发送邮箱验证码 |
| 认证 | `POST` | `/api/v1/auth/login` | 公开 | 邮箱验证码登录 |
| 认证 | `GET` | `/api/v1/auth/profile` | 登录 | 当前用户资料 |
| 认证 | `POST` | `/api/v1/auth/refresh` | 公开 | 刷新访问令牌 |
| 认证 | `POST` | `/api/v1/auth/logout` | 登录 | 登出并失效令牌 |
| 图片识别 | `POST` | `/api/v1/recognition/analyze` | 登录 | 上传图片并创建异步识别任务 |
| 图片识别 | `GET` | `/api/v1/recognition/status/{sessionId}` | 登录 | 查询识别任务状态 |
| 图片识别 | `POST` | `/api/v1/recognition/{sessionId}/select-product` | 登录 | 多商品候选选择 |
| 图片识别 | `GET` | `/api/v1/recognition/{sessionId}/candidates/{candidateId}/image` | 登录 | 获取候选裁剪图 |
| 图片识别 | `POST` | `/api/v1/recognition/{sessionId}/archive` | 登录 | 归档会话商品快照 |
| 属性修正 | `PATCH` | `/api/v1/recognition/{sessionId}/attributes` | 登录 | 新版统一动作属性修正 |
| 属性修正 | `PUT` | `/api/v1/recognition/attributes` | 登录 | 旧版属性修正 |
| 属性修正 | `GET` | `/api/v1/recognition/attribute-options` | 登录 | 获取属性候选值 |
| 属性反馈 | `GET` | `/api/v1/recognition/feedback/stats` | 管理员 | 属性修正统计 |
| 商品搜索 | `POST` | `/api/v1/search/products` | 登录 | 多平台商品搜索 |
| NLP | `POST` | `/api/v1/nlp/parse` | 登录 | 自然语言解析 |
| NLP | `POST` | `/api/v1/nlp/filter` | 登录 | 自然语言筛选候选池 |
| NLP | `DELETE` | `/api/v1/nlp/filter/{sessionId}/field/{fieldName}` | 登录 | 删除筛选字段 |
| NLP | `DELETE` | `/api/v1/nlp/filter/{sessionId}/tag/{tagId}` | 登录 | 删除结构化标签 |
| NLP | `DELETE` | `/api/v1/nlp/filter/{sessionId}` | 登录 | 清空筛选 |
| NLP | `PUT` | `/api/v1/nlp/filter/{sessionId}` | 登录 | 替换筛选 |
| NLP | `POST` | `/api/v1/nlp/filter/{sessionId}/undo` | 登录 | 旧版 NLP 撤回 |
| 推荐 | `GET` | `/api/v1/suggestions/cards` | 登录 | 获取推荐卡片 |
| 推荐 | `POST` | `/api/v1/suggestions/action` | 登录 | 新版推荐动作 |
| 推荐 | `POST` | `/api/v1/suggestions/execute` | 登录 | 旧版推荐动作 |
| 推荐 | `POST` | `/api/v1/suggestions/undo` | 登录 | 旧版推荐撤回 |
| 统一动作 | `POST` | `/api/v1/actions/execute` | 登录 | 统一执行 NLP、推荐、修正、排序等动作 |
| 统一动作 | `POST` | `/api/v1/actions/undo` | 登录 | 统一撤回 |
| 统一动作 | `GET` | `/api/v1/actions/can-undo` | 登录 | 查询是否可撤回 |
| 收藏 | `POST` | `/api/v1/favorites` | 登录 | 添加或更新收藏 |
| 收藏 | `GET` | `/api/v1/favorites` | 登录 | 收藏列表 |
| 收藏 | `DELETE` | `/api/v1/favorites/{productId}` | 登录 | 删除收藏 |
| 历史 | `GET` | `/api/v1/history` | 登录 | 分页查询识别历史 |
| 历史 | `GET` | `/api/v1/history/{sessionId}/products` | 登录 | 历史商品快照 |
| 历史 | `GET` | `/api/v1/history/{sessionId}/image` | 登录 | 历史图片 |
| 历史 | `DELETE` | `/api/v1/history/{sessionId}` | 登录 | 删除历史 |
| 价格提醒 | `POST` | `/api/v1/price-alerts` | 登录 | 创建或更新价格提醒 |
| 价格提醒 | `GET` | `/api/v1/price-alerts` | 登录 | 价格提醒列表 |
| 价格提醒 | `DELETE` | `/api/v1/price-alerts/{productId}` | 登录 | 删除价格提醒 |
| 价格历史 | `GET` | `/api/v1/price-history/{productId}` | 登录 | 商品价格历史 |
| 指标 | `GET` | `/api/v1/metrics/summary` | 管理员 | 指标摘要 |
| 指标 | `GET` | `/api/v1/metrics/search` | 管理员 | 搜索指标 |
| 指标 | `GET` | `/api/v1/metrics/nlp` | 管理员 | NLP 指标 |
| 指标 | `GET` | `/api/v1/metrics/platforms` | 管理员 | 平台配置与熔断状态 |
| 指标 | `GET` | `/api/v1/metrics/log` | 管理员 | 输出指标日志 |

## 5. 认证接口

### 5.1 发送邮箱验证码

`POST /api/v1/auth/send-code`

请求：

```json
{
  "email": "user@example.com"
}
```

响应：

```json
{
  "code": 200,
  "message": "ok",
  "data": null,
  "trace_id": null
}
```

说明：邮箱格式必须合法；发送过于频繁时返回 `429`。

### 5.2 邮箱验证码登录

`POST /api/v1/auth/login`

请求：

```json
{
  "email": "user@example.com",
  "code": "123456"
}
```

响应：

```json
{
  "code": 200,
  "message": "ok",
  "data": {
    "token": "<access_token>",
    "refresh_token": "<refresh_token>",
    "user_id": 1,
    "email": "user@example.com"
  },
  "trace_id": null
}
```

### 5.3 获取用户资料

`GET /api/v1/auth/profile`

请求头：

```http
Authorization: Bearer <access_token>
```

响应 `data`：

```json
{
  "id": 1,
  "email": "user@example.com",
  "created_at": "2026-06-09T10:00:00Z"
}
```

### 5.4 刷新 Token

`POST /api/v1/auth/refresh`

请求：

```json
{
  "refresh_token": "<refresh_token>"
}
```

响应 `data` 与登录接口一致。

### 5.5 登出

`POST /api/v1/auth/logout`

请求头：

```http
Authorization: Bearer <access_token>
```

请求体可选：

```json
{
  "refresh_token": "<refresh_token>"
}
```

说明：服务端会将 access token 加入失效列表，并可同步失效 refresh token。

## 6. 图片识别接口

### 6.1 上传图片识别

`POST /api/v1/recognition/analyze`

Content-Type：`multipart/form-data`

表单参数：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `image` | 是 | 商品图片，支持 JPEG、PNG、WebP 魔数校验 |
| `region` | 否 | 地区，用于平台与区域策略 |
| `previous_session_id` | 否 | 上一识别会话，提交新识别时尝试归档上一会话 |

响应 `data`：

```json
{
  "session_id": "rec_abc123",
  "status": "PENDING",
  "websocket_topic": "/topic/recognition/rec_abc123",
  "estimated_ms": 30000
}
```

说明：

- 上传大小由 `RECOGNITION_MAX_UPLOAD_BYTES` 控制，默认约 25MB。
- 图片为空、类型不合法或质量检测不通过会返回 `400`。
- 识别为异步任务，客户端应轮询状态或订阅 WebSocket。

### 6.2 查询识别状态

`GET /api/v1/recognition/status/{sessionId}`

响应 `data`：

```json
{
  "session_id": "rec_abc123",
  "status": "COMPLETED",
  "result": {
    "session_id": "rec_abc123",
    "category": {
      "level1": "数码",
      "level2": "手机配件",
      "level3": "手机壳",
      "confidence": 0.92
    },
    "attributes": {
      "color": {
        "value": "黑色",
        "confidence": 0.86,
        "verified": false
      }
    },
    "keywords": ["手机壳", "黑色"],
    "overall_confidence": 0.9,
    "platform_stats": []
  },
  "error": null,
  "created_at": "2026-06-09T10:00:00Z",
  "completed_at": "2026-06-09T10:00:04Z",
  "candidates": [
    {
      "candidate_id": "candidate-1",
      "bbox": [20, 30, 200, 260],
      "category": "手机壳",
      "brand": "Apple",
      "confidence": 0.91,
      "preview_image_url": "/api/v1/recognition/rec_abc123/candidates/candidate-1/image"
    }
  ],
  "progress_step": "done",
  "confidence_hint": null
}
```

常见状态：`PENDING`、`RUNNING`、`COMPLETED`、`FAILED`。

### 6.3 多商品候选选择

`POST /api/v1/recognition/{sessionId}/select-product`

请求：

```json
{
  "candidate_id": "candidate-1"
}
```

响应：`ApiResponse<AsyncRecognitionResponse>`。

### 6.4 获取候选裁剪图

`GET /api/v1/recognition/{sessionId}/candidates/{candidateId}/image`

返回：`image/jpeg`。该接口不包裹 `ApiResponse`。

### 6.5 归档会话

`POST /api/v1/recognition/{sessionId}/archive`

说明：将当前会话展示的商品快照归档到 MySQL，用于历史记录与会话恢复。

### 6.6 属性修正

新版接口：

`PATCH /api/v1/recognition/{sessionId}/attributes`

请求：

```json
{
  "session_id": "rec_abc123",
  "attribute": "color",
  "old_value": "灰色",
  "new_value": "黑色"
}
```

响应：`ApiResponse<ActionResult>`。

旧版兼容接口：

`PUT /api/v1/recognition/attributes`

响应：`ApiResponse<AttributeCorrectionResult>`。

### 6.7 属性候选项

`GET /api/v1/recognition/attribute-options?category=手机壳&attribute=color&session_id=rec_abc123`

响应：

```json
{
  "code": 200,
  "message": "ok",
  "data": {
    "options": ["黑色", "白色", "蓝色"]
  },
  "trace_id": null
}
```

### 6.8 属性反馈统计

`GET /api/v1/recognition/feedback/stats`

权限：管理员。

响应 `data` 为修正统计列表，包含 `attribute_name`、`vlm_output`、`user_correction`、`count` 等字段。

## 7. 商品搜索接口

### 7.1 多平台商品搜索

`POST /api/v1/search/products`

请求：

```json
{
  "session_id": "rec_abc123",
  "attributes": {
    "keyword": "手机壳",
    "brand": "Apple",
    "color": "黑色"
  },
  "filter": {
    "price_range": { "min": 50, "max": 300 },
    "platforms": ["pdd", "taobao"],
    "self_operated": null,
    "colors": ["黑色"],
    "brands": ["Apple"],
    "rating_min": 4.5,
    "sort_by": "price",
    "sort_order": "asc",
    "keyword": "防摔",
    "attributes": {},
    "exclude_roles": [],
    "capabilities": {}
  },
  "page": 1,
  "page_size": 50,
  "recall_size": 300,
  "client_type": "app"
}
```

参数说明：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `session_id` | 否 | 识别会话 ID；传入时会校验资源归属 |
| `attributes` | 否 | 识别属性或搜索关键词，最多 20 项 |
| `filter` | 否 | 筛选条件 |
| `page` | 否 | 页码，默认 1 |
| `page_size` | 否 | 每页数量，默认 50，最大 100 |
| `recall_size` | 否 | 候选召回量，默认 300，最大 2000 |
| `client_type` | 否 | 客户端类型，如 `app`、`overlay` |

响应 `data`：

```json
{
  "total": 80,
  "products": [],
  "platform_stats": [
    {
      "platform": "pdd",
      "min_price": 39.9,
      "avg_price": 58.5,
      "count": 30
    }
  ],
  "suggestion_cards": [],
  "relaxed": false,
  "filter_tags": ["黑色", "价格≤300"],
  "total_in_pool": 300,
  "need_expand": false,
  "need_relax_hint": false,
  "cache_expired": false,
  "search_run_id": "run_xxx"
}
```

说明：

- 后端并行调用拼多多、淘宝联盟、eBay 等平台。
- 平台调用由 `PlatformCircuitBreaker` 做熔断保护。
- 搜索过程会通过 WebSocket 推送阶段性结果到 `/topic/search/{sessionId}`。
- 传入 `session_id` 且搜索成功时，会归档当前展示商品快照。

## 8. NLP 筛选接口

### 8.1 自然语言解析

`POST /api/v1/nlp/parse`

请求：

```json
{
  "session_id": "rec_abc123",
  "user_input": "只看黑色，价格 300 以内，评分高一点",
  "context": {
    "product_name": "手机壳",
    "category": "手机配件",
    "history": []
  }
}
```

响应 `data`：

```json
{
  "filter": {
    "price_range": { "min": null, "max": 300 },
    "colors": ["黑色"],
    "rating_min": 4.5,
    "sort_order": "desc"
  },
  "confidence": 0.86,
  "from_cache": false,
  "decision": "parsed",
  "message": "已识别筛选条件",
  "clauses": [
    {
      "type": "preference",
      "field": "high_rating",
      "confidence": 0.8,
      "raw_text": "评分高一点"
    }
  ]
}
```

### 8.2 自然语言筛选候选池

`POST /api/v1/nlp/filter`

请求：

```json
{
  "session_id": "rec_abc123",
  "user_input": "可以带上飞机的充电宝，别太贵",
  "context": {
    "product_name": "充电宝",
    "category": "数码配件"
  }
}
```

响应：`ApiResponse<NlpFilterResult>`。

关键字段：

| 字段 | 说明 |
| --- | --- |
| `products` | 筛选后的商品列表 |
| `filter` | 合并后的筛选条件 |
| `filter_tags` | 旧版标签文本 |
| `structured_filter_tags` | 带 `filter_path` 的结构化标签 |
| `total_in_pool` | 候选池总数 |
| `result_count` | 筛选后数量 |
| `need_expand` | 是否建议扩展召回 |
| `need_relax_hint` | 是否建议放宽条件 |
| `cache_expired` | 候选池是否过期 |
| `new_search_intent` | 是否检测到换品类意图 |
| `can_undo` | 是否可撤回 |
| `filter_applied` | 是否真正提交筛选 |
| `kept_previous_results` | 是否保留旧结果 |

说明：

- 后端优先读取服务端候选池，不完全信任客户端列表。
- 候选池过期时，会尝试从历史识别信息重新搜索。
- 若筛选为 0 结果，ZeroResultGuard 会回滚，返回 `filter_applied=false`。

### 8.3 删除、替换、撤回筛选

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `DELETE` | `/api/v1/nlp/filter/{sessionId}/field/{fieldName}` | 删除筛选字段 |
| `DELETE` | `/api/v1/nlp/filter/{sessionId}/tag/{tagId}` | 删除结构化筛选标签 |
| `DELETE` | `/api/v1/nlp/filter/{sessionId}` | 清空筛选 |
| `PUT` | `/api/v1/nlp/filter/{sessionId}` | 用完整 `SearchFilter` 替换筛选状态 |
| `POST` | `/api/v1/nlp/filter/{sessionId}/undo` | 旧版 NLP 撤回 |

新客户端建议优先使用统一动作接口 `/api/v1/actions/undo`。

## 9. 推荐与统一动作接口

### 9.1 获取推荐卡片

`GET /api/v1/suggestions/cards?client_type=app&session_id=rec_abc123`

响应 `data`：

```json
{
  "cards": [
    {
      "id": "sort_price",
      "title": "按价格排序",
      "subtitle": "优先查看低价商品",
      "icon": "sort",
      "action": "sort_by_price_asc",
      "priority": 10,
      "badge": null,
      "reason": "价格差异明显",
      "metric": null,
      "action_label": "应用",
      "tone": "neutral",
      "action_type": "FILTER_ACTION"
    }
  ],
  "insight_status": "READY"
}
```

`insight_status` 取值：

| 值 | 说明 |
| --- | --- |
| `READY` | AI 导购分析已完成 |
| `PENDING` | AI 分析后台生成中 |
| `FAILED` | AI 分析失败，使用兜底建议 |
| `EMPTY` | AI 分析完成但没有强结论 |

### 9.2 执行推荐动作

新版：

`POST /api/v1/suggestions/action`

请求：

```json
{
  "session_id": "rec_abc123",
  "action": "sort_by_price_asc",
  "current_products": [],
  "current_filter": {},
  "context": {
    "category": "手机配件",
    "source": "suggestion_card"
  }
}
```

响应：`ApiResponse<ActionResult>`。

旧版：

`POST /api/v1/suggestions/execute`

响应：`ApiResponse<SuggestionExecuteResult>`。

### 9.3 统一动作执行

`POST /api/v1/actions/execute`

请求：

```json
{
  "action_id": "nlp-001",
  "source": "nlp",
  "session_id": "rec_abc123",
  "raw_text": "只看黑色",
  "payload": {
    "action": null,
    "field": null,
    "value": null,
    "tag_id": null,
    "filter_path": null,
    "sort_by": null,
    "context": {
      "category": "手机壳"
    },
    "filter_spec": null
  },
  "client_request_id": "req-001"
}
```

`source` 常见值：

| source | 说明 |
| --- | --- |
| `nlp` | 自然语言筛选 |
| `suggestion` | 推荐卡片动作 |
| `correction` | 属性修正 |
| `tag_delete` | 删除筛选标签 |
| `sort` | 排序 |
| `manual_filter` | 手动筛选 |

响应：`ApiResponse<ActionResult>`。

### 9.4 统一撤回

`POST /api/v1/actions/undo?session_id=rec_abc123`

说明：

- 支持撤回 NLP、推荐、属性修正、标签删除、排序等操作。
- 当前版本撤回 NLP 时会结合 `NlpStateStackService` 恢复上一层 NLP 状态，包括筛选条件、商品列表和标签。
- 属性修正撤回会尝试恢复历史属性，并刷新 active task 中的识别结果。

响应：`ApiResponse<ActionResult>`。

### 9.5 是否可撤回

`GET /api/v1/actions/can-undo?session_id=rec_abc123`

响应：

```json
{
  "code": 200,
  "message": "ok",
  "data": {
    "canUndo": true
  },
  "trace_id": null
}
```

注意：该接口返回 Map，字段当前为 `canUndo`。

## 10. 收藏与历史接口

### 10.1 添加或更新收藏

`POST /api/v1/favorites`

请求：

```json
{
  "product_id": "pdd_10001",
  "platform": "pdd",
  "title": "黑色防摔手机壳",
  "image_url": "https://example.com/a.jpg",
  "price": 39.9,
  "detail_url": "https://example.com/detail",
  "updated_at": 1780912800000
}
```

响应：`ApiResponse<FavoriteCard>`。

说明：若客户端 `updated_at` 早于服务端记录，服务端保留较新的记录。

### 10.2 收藏列表

`GET /api/v1/favorites`

响应：`ApiResponse<List<FavoriteCard>>`，最多返回最近 50 条收藏。

扩展字段：

| 字段 | 说明 |
| --- | --- |
| `current_price` | 当前价格 |
| `price_change` | 价格变动，当前可为空 |
| `price_lowest` | 是否为近 90 天低价 |

### 10.3 删除收藏

`DELETE /api/v1/favorites/{productId}`

说明：删除收藏时会同步删除关联价格提醒。

### 10.4 历史列表

`GET /api/v1/history?page=1&size=20`

参数：

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `page` | 1 | 页码，从 1 开始 |
| `size` | 20 | 每页数量，范围 1 到 50 |

响应 `data`：

```json
{
  "total": 120,
  "page": 1,
  "size": 20,
  "items": [
    {
      "session_id": "rec_abc123",
      "image_url": "/api/v1/history/rec_abc123/image",
      "category": {},
      "attributes": {},
      "keywords": ["手机壳"],
      "confidence": 0.9,
      "created_at": "2026-06-09T10:00:00Z",
      "products": []
    }
  ]
}
```

### 10.5 历史商品与图片

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v1/history/{sessionId}/products` | 查询会话商品快照 |
| `GET` | `/api/v1/history/{sessionId}/image` | 返回历史图片 `image/jpeg` |
| `DELETE` | `/api/v1/history/{sessionId}` | 删除历史记录和图片 |

## 11. 价格提醒接口

### 11.1 创建或更新价格提醒

`POST /api/v1/price-alerts`

请求：

```json
{
  "product_id": "pdd_10001",
  "target_price": 29.9
}
```

响应 `data`：

```json
{
  "product_id": "pdd_10001",
  "platform": "pdd",
  "title": "黑色防摔手机壳",
  "image_url": "https://example.com/a.jpg",
  "target_price": 29.9,
  "current_price": 39.9,
  "favorite_price": 39.9,
  "active": true,
  "triggered_at": null,
  "created_at": "2026-06-09T10:00:00Z"
}
```

当前版本行为：

- `target_price` 必须大于等于 `0.01`。
- 如果已有 active 提醒，则更新目标价。
- 如果当前收藏价已经小于等于目标价，提醒会直接保存为 `active=false`，并设置 `triggered_at=当前时间`。
- 如果商品已收藏，会补充标题、图片和收藏价格。

### 11.2 查询价格提醒

`GET /api/v1/price-alerts`

响应：`ApiResponse<List<PriceAlertCard>>`。

### 11.3 删除价格提醒

`DELETE /api/v1/price-alerts/{productId}`

成功响应 `data=null`。

### 11.4 查询价格历史

`GET /api/v1/price-history/{productId}`

说明：只能查询当前用户已收藏商品的价格历史。

响应 `data`：

```json
{
  "product_id": "pdd_10001",
  "platform": "pdd",
  "lowest_30d": 29.9,
  "entries": [
    {
      "price": 39.9,
      "recorded_at": "2026-06-09T10:00:00Z"
    }
  ]
}
```

## 12. 健康检查与指标

### 12.1 基础健康检查

`GET /api/v1/health`

响应：

```json
{
  "code": 200,
  "message": "ok",
  "data": {
    "status": "up",
    "time": "2026-06-09T10:00:00Z"
  },
  "trace_id": null
}
```

### 12.2 深度健康检查

`GET /api/v1/health/deep`

响应 `data`：

```json
{
  "mysql": "up",
  "redis": "up",
  "status": "up",
  "time": "2026-06-09T10:00:00Z"
}
```

### 12.3 指标接口

权限：管理员。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v1/metrics/summary` | 性能指标摘要，含平台配置和熔断状态 |
| `GET` | `/api/v1/metrics/search` | 搜索缓存命中率 |
| `GET` | `/api/v1/metrics/nlp` | NLP LLM 回退率 |
| `GET` | `/api/v1/metrics/platforms` | 平台启用状态、超时、权重、熔断状态 |
| `GET` | `/api/v1/metrics/log` | 将指标摘要打印到服务端日志 |

## 13. WebSocket / STOMP 推送

WebSocket 端点：

```text
/ws/recognition
```

消息代理：

| 配置 | 值 |
| --- | --- |
| Broker 前缀 | `/topic` |
| 应用前缀 | `/app` |

客户端在 STOMP `CONNECT` 帧中携带 JWT：

```text
Authorization: Bearer <access_token>
```

或：

```text
token: <access_token>
```

若配置 `VISIONCART_WS_QUERY_TOKEN_ENABLED=true`，SockJS fallback 可从查询参数读取 token。

### 13.1 识别进度

订阅：

```text
/topic/recognition/{sessionId}
```

权限：只能订阅当前用户自己的 active recognition task。

消息结构：`RecognitionTaskResult`。

### 13.2 搜索进度

订阅：

```text
/topic/search/{sessionId}
```

权限：只能订阅当前用户自己的 active task 或历史会话。

消息结构：

```json
{
  "session_id": "rec_abc123",
  "products": [],
  "total_count": 50,
  "staging": true,
  "search_run_id": "run_xxx"
}
```

`staging=true` 表示阶段性结果，`staging=false` 表示最终结果。

### 13.3 价格提醒

订阅：

```text
/topic/price-alert/{userId}
```

权限：只能订阅与当前 JWT 用户 ID 相同的主题。

消息结构由服务端 Map 构造，字段为 camelCase：

```json
{
  "type": "price_alert",
  "alertType": "target_price",
  "productId": "pdd_10001",
  "platform": "pdd",
  "title": "黑色防摔手机壳",
  "currentPrice": 29.9,
  "targetPrice": 29.9,
  "favoritePrice": 39.9,
  "message": "价格提醒消息"
}
```

## 14. 典型联调流程

### 14.1 登录

```bash
curl -X POST http://localhost:8080/api/v1/auth/send-code \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com"}'

curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","code":"123456"}'
```

### 14.2 图片识别

```bash
curl -X POST http://localhost:8080/api/v1/recognition/analyze \
  -H "Authorization: Bearer <access_token>" \
  -F "image=@sample.jpg" \
  -F "region=CN"

curl http://localhost:8080/api/v1/recognition/status/<session_id> \
  -H "Authorization: Bearer <access_token>"
```

### 14.3 商品搜索

```bash
curl -X POST http://localhost:8080/api/v1/search/products \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <access_token>" \
  -d '{
    "session_id": "<session_id>",
    "attributes": {"keyword":"手机壳"},
    "filter": {"price_range":{"min":null,"max":100}},
    "page": 1,
    "page_size": 20,
    "client_type": "app"
  }'
```

### 14.4 NLP 筛选

```bash
curl -X POST http://localhost:8080/api/v1/nlp/filter \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <access_token>" \
  -d '{
    "session_id": "<session_id>",
    "user_input": "只看黑色，300元以内，评分高一点",
    "context": {
      "product_name": "手机壳",
      "category": "手机配件"
    }
  }'
```

## 15. 安全与部署说明

- 所有敏感信息必须通过 `.env` 或服务器环境变量注入，不应提交到仓库或写入 Android 代码。
- Android 客户端只保存访问后端所需的 JWT，不保存第三方平台 API 密钥。
- 生产环境建议保持 Swagger/OpenAPI 关闭。
- WebSocket 订阅会校验资源归属，客户端不能订阅他人的 `session_id` 或 `user_id`。
- 图片接口返回二进制资源，客户端需要按 `image/jpeg` 处理。
- 价格提醒、收藏、历史记录均按当前登录用户隔离。
