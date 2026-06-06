# VisionCart 平台能力配置表与统一性能指标实现总结

## 实现概述

本次实现为 VisionCart 项目添加了两个核心功能：

1. **平台能力配置表** - 支持平台开关、超时配置、权重、降级策略、区域策略
2. **统一性能指标** - 收集和暴露关键性能指标

## 实现的功能

### 1. 平台能力配置表

#### 配置结构
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
    taobao:
      enabled: true
      timeout-ms: 2500
      weight: 1.1
      fallback-enabled: true
      fallback-priority: 2
      region-strategy: domestic
      max-retries: 2
      retry-delay-ms: 500
      rate-limit-per-second: 10
      circuit-breaker-enabled: true
    ebay:
      enabled: true
      timeout-ms: 3000
      weight: 0.8
      fallback-enabled: true
      fallback-priority: 3
      region-strategy: international
      max-retries: 2
      retry-delay-ms: 500
      rate-limit-per-second: 10
      circuit-breaker-enabled: true
```

#### 配置项说明

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `enabled` | 平台开关 | `true` |
| `timeout-ms` | 平台超时时间（毫秒） | `2500` |
| `weight` | 平台权重（影响排序） | `1.0` |
| `fallback-enabled` | 是否启用降级 | `true` |
| `fallback-priority` | 降级优先级（数字越小优先级越高） | `1` |
| `region-strategy` | 区域策略（global/domestic/international） | `global` |
| `max-retries` | 最大重试次数 | `2` |
| `retry-delay-ms` | 重试延迟（毫秒） | `500` |
| `rate-limit-per-second` | 每秒请求限制 | `10` |
| `circuit-breaker-enabled` | 是否启用熔断器 | `true` |

#### 环境变量覆盖

所有配置都可以通过环境变量覆盖：

```bash
# 禁用拼多多平台
export PLATFORM_PDD_ENABLED=false

# 设置淘宝超时为 3 秒
export PLATFORM_TAOBAO_TIMEOUT_MS=3000

# 设置 eBay 权重为 0.5
export PLATFORM_EBAY_WEIGHT=0.5
```

### 2. 统一性能指标

#### 支持的指标

| 指标类别 | 指标名称 | 类型 | 说明 |
|---------|---------|------|------|
| 识别 | `recognition.latency` | Timer | 识别总延迟 |
| 识别 | `vision_model.latency` | Timer | 视觉模型调用延迟 |
| 搜索 | `search.total_latency` | Timer | 搜索总延迟 |
| 搜索 | `search.platform_latency` | Timer | 各平台搜索延迟 |
| 搜索 | `search.cache_hit_rate` | Gauge | 搜索缓存命中率 |
| 搜索 | `search.cache_hit_count` | Gauge | 缓存命中次数 |
| 搜索 | `search.cache_miss_count` | Gauge | 缓存未命中次数 |
| NLP | `nlp.parse_latency` | Timer | NLP 解析延迟 |
| NLP | `nlp.llm_fallback_rate` | Gauge | NLP LLM 回退率 |
| NLP | `nlp.llm_fallback_count` | Gauge | LLM 回退次数 |
| NLP | `nlp.total_requests` | Gauge | NLP 总请求数 |
| 候选 | `candidate.filter_latency` | Timer | 候选过滤延迟 |
| 候选 | `candidate.input_count` | Gauge | 过滤前候选数 |
| 候选 | `candidate.output_count` | Gauge | 过滤后候选数 |
| 候选 | `candidate.filter_rate` | Gauge | 过滤通过率 |
| 平台 | `platform.circuit_open_count` | Counter | 熔断器打开次数 |
| 平台 | `platform.circuit_close_count` | Counter | 熔断器关闭次数 |
| 平台 | `platform.circuit_half_open_count` | Counter | 熔断器半开次数 |

#### API 接口

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

## 实现的文件

### 新增文件

1. **PlatformConfigProperties.java** - 平台配置属性类
2. **PerformanceMetricsService.java** - 性能指标服务
3. **MetricsController.java** - 性能指标 REST 控制器
4. **PerformanceMetricsServiceTest.java** - 性能指标服务单元测试
5. **performance-metrics-guide.md** - 性能指标监控指南
6. **grafana-dashboard.json** - Grafana 仪表板配置
7. **implementation-summary.md** - 本实现总结文档

### 修改文件

1. **VisionCartProperties.java** - 添加平台配置类
2. **application.yml** - 添加平台配置和 Actuator 配置
3. **SearchOrchestrator.java** - 集成性能指标收集
4. **PlatformCircuitBreaker.java** - 集成性能指标收集
5. **CLAUDE.md** - 更新项目文档

## 测试结果

所有单元测试均已通过：

```
> Task :backend:test
BUILD SUCCESSFUL in 57s
4 actionable tasks: 2 executed, 2 up-to-date
```

## 使用示例

### 1. 配置平台

在 `application.yml` 中配置平台参数：

```yaml
visioncart:
  platforms:
    pdd:
      enabled: true
      timeout-ms: 2500
      weight: 1.0
    taobao:
      enabled: true
      timeout-ms: 2500
      weight: 1.1
    ebay:
      enabled: true
      timeout-ms: 3000
      weight: 0.8
```

### 2. 查看性能指标

```bash
# 获取所有指标摘要
curl http://localhost:8080/api/v1/metrics/summary

# 获取搜索缓存命中率
curl http://localhost:8080/api/v1/metrics/search

# 获取 NLP LLM 回退率
curl http://localhost:8080/api/v1/metrics/nlp

# 获取平台熔断器状态
curl http://localhost:8080/api/v1/metrics/platforms
```

### 3. 监控仪表板

1. 配置 Prometheus 数据源指向 `http://localhost:8080/actuator/prometheus`
2. 导入 `docs/grafana-dashboard.json` 到 Grafana
3. 查看实时性能指标

## 后续优化建议

1. **告警系统** - 基于性能指标设置告警规则
2. **容量规划** - 根据指标趋势进行容量规划
3. **性能优化** - 根据延迟指标优化慢查询
4. **缓存策略** - 根据缓存命中率调整缓存策略
5. **平台调权** - 根据平台性能调整权重配置

## 总结

本次实现为 VisionCart 项目提供了完整的平台配置和性能监控能力，支持：

- ✅ 平台开关控制
- ✅ 平台超时配置
- ✅ 平台权重配置
- ✅ 平台降级策略
- ✅ 区域策略配置
- ✅ 统一性能指标收集
- ✅ REST API 暴露指标
- ✅ Prometheus 集成
- ✅ Grafana 仪表板支持
- ✅ 单元测试覆盖

这些功能为 VisionCart 的运维和优化提供了有力支持，体现了"数据源模块易扩展"的设计目标。
