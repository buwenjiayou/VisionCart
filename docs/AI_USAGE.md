# AI 使用总结

## Spring AI 编排

后端使用 Spring AI 作为大语言模型编排层，避免在业务代码中散落直接 HTTP 调用。`SpringAiNlpService` 负责：

- 提示词模板管理（通过 `PromptLoader` 从 YAML 文件加载，支持版本管理）
- 规则提取结果作为上下文提示
- 通过 `ChatClient` 调用大语言模型（含重试与指数退避）
- JSON 结构化输出解析与 Schema 校验（`AiOutputValidator`）
- AI 调用日志、耗时追踪与 Micrometer 指标（`AiTraceService`）

视觉模型通过 `VisionModelService` 抽象封装，当前实现为 `DoubaoVisionClient`，用于调用兼容 OpenAI 接口格式的多模态服务。

## 提示词策略

自然语言解析提示词明确约束：

- 只提取用户明确提出的条件
- 不编造品牌、价格、平台
- 平台和排序字段使用枚举
- 只输出 JSON，不输出 Markdown

提示词存储在 `src/main/resources/prompts/` 目录下的 YAML 文件中，通过 `PromptLoader` 组件加载，支持版本管理和热更新（修改 YAML 后重新部署即可）。

## 缓存降本

搜索结果通过 `SearchOrchestrator` 使用 Redis 缓存，TTL 为 5 分钟。自然语言筛选先走规则引擎，命中价格、平台、颜色、评分、排序等高频条件时不调用 LLM。

## 安全防护

- **Prompt 注入防护**：`PromptSanitizer` 在 LLM 调用前检测并过滤注入指令（中英文）。
- **输出 Schema 校验**：`AiOutputValidator` 对 LLM 返回的筛选条件进行枚举校验和范围限制。
- **输入验证**：DTO 使用 `@Size` 约束限制输入长度，Controller 验证图片格式和大小。

## 工程原则

- 第三方模型密钥只放在服务端环境变量中。
- Android 和 Web 不保存任何模型或平台密钥。
- 模型输出必须经过结构化解析和业务字段校验。
- AI 生成代码后必须用工程边界、接口契约和验收路径反向校验。
