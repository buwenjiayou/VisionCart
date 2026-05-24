# VisionCart 架构设计

## 总览

VisionCart 采用 Android + Spring Boot + Spring AI + MySQL + Redis 架构。客户端负责拍照、相册、悬浮窗和导购交互；服务端负责 AI 编排、平台检索、比价排序、用户数据闭环和真实第三方 API 集成。

```text
Android 应用 / Web 控制台
        |
        | HTTP/HTTPS + JWT
        v
Spring Boot API
  |-- RecognitionService -> VisionModelService -> DoubaoVisionClient
  |-- SpringAiNlpService -> Spring AI ChatClient -> 大语言模型服务
  |-- SearchOrchestrator -> PlatformSearchService -> PDD/Taobao/eBay
  |-- SuggestionService -> 智能建议卡片与动作执行
  |-- UserDataController -> 历史、收藏、反馈
        |
        +-- MySQL: 用户、历史、收藏、反馈、价格提醒
        +-- Redis: 验证码、NLP 语义缓存
```

## AI 处理流程

1. 图片上传后进入 `RecognitionService`。
2. `DoubaoVisionClient` 调用视觉模型并解析结构化 JSON。
3. 识别结果写入 MySQL，形成可回看的识物历史。
4. 商品检索使用识别属性生成关键词，多平台并行检索后统一去重、排序、价格统计。
5. 用户自然语言输入先走规则解析；规则不足时由 Spring AI ChatClient 调用 LLM 输出结构化 JSON。
6. NLP 结果使用统一 cache key 写入 Redis，重复语义直接命中缓存。
7. 建议卡片根据商品状态动态生成，点击后更新商品列表与下一组卡片。

## 模块边界

- Android 只做图片采集、权限、UI 状态和轻量缓存，不保存任何第三方平台密钥。
- 后端统一封装 AI、平台 API、缓存和数据持久化，第三方密钥只从环境变量读取。
- Web 目录提供轻量控制台和项目入口，用于连接真实后端完成链路验证。
- MySQL 和 Redis 是工程运行依赖，生产环境应使用托管服务或独立服务实例。

## 失败处理

- 外部 AI 配置缺失或调用失败：接口返回明确错误，提示检查模型环境变量。
- 平台 API 配置缺失：对应平台检索会跳过并记录日志，其它已配置平台继续返回。
- Redis 不可用：NLP 缓存临时使用进程内缓存，但验证码仍应在生产环境使用 Redis。
- MySQL 不可用：后端启动或业务写入会失败，应先修复数据库连接。
