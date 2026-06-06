# VisionCart 性能指标监控指南

## 概述

VisionCart 集成了统一的性能指标监控系统，基于 Micrometer + Spring Boot Actuator，支持以下指标：

## 支持的性能指标

### 识别相关指标
| 指标名称 | 类型 | 说明 |
|---------|------|------|
| `recognition.latency` | Timer | 识别总延迟 |
| `vision_model.latency` | Timer | 视觉模型调用延迟 |

### 搜索相关指标
| 指标名称 | 类型 | 说明 |
|---------|------|------|
| `search.total_latency` | Timer | 搜索总延迟 |
| `search.platform_latency` | Timer | 各平台搜索延迟（按平台标签） |
| `search.cache_hit_rate` | Gauge | 搜索缓存命中率 |
| `search.cache_hit_count` | Gauge | 缓存命中次数 |
| `search.cache_miss_count` | Gauge | 缓存未命中次数 |

### NLP 相关指标
| 指标名称 | 类型 | 说明 |
|---------|------|------|
| `nlp.parse_latency` | Timer | NLP 解析延迟 |
| `nlp.llm_fallback_rate` | Gauge | NLP LLM 回退率 |
| `nlp.llm_fallback_count` | Gauge | LLM 回退次数 |
| `nlp.total_requests` | Gauge | NLP 总请求数 |

### 候选过滤指标
| 指标名称 | 类型 | 说明 |
|---------|------|------|
| `candidate.filter_latency` | Timer | 候选过滤延迟 |
| `candidate.input_count` | Gauge | 过滤前候选数 |
| `candidate.output_count` | Gauge | 过滤后候选数 |
| `candidate.filter_rate` | Gauge | 过滤通过率 |

### 平台熔断器指标
| 指标名称 | 类型 | 说明 |
|---------|------|------|
| `platform.circuit_open_count` | Counter | 熔断器打开次数（按平台） |
| `platform.circuit_close_count` | Counter | 熔断器关闭次数（按平台） |
| `platform.circuit_half_open_count` | Counter | 熔断器半开次数（按平台） |

## 访问方式

### 1. REST API 接口

#### 获取所有指标摘要
```bash
curl http://localhost:8080/api/v1/metrics/summary
```

#### 获取搜索指标
```bash
curl http://localhost:8080/api/v1/metrics/search
```

#### 获取 NLP 指标
```bash
curl http://localhost:8080/api/v1/metrics/nlp
```

#### 获取平台指标
```bash
curl http://localhost:8080/api/v1/metrics/platforms
```

#### 打印指标到服务器日志
```bash
curl http://localhost:8080/api/v1/metrics/log
```

### 2. Spring Boot Actuator

#### Prometheus 格式
```bash
curl http://localhost:8080/actuator/prometheus
```

#### JSON 格式
```bash
curl http://localhost:8080/actuator/metrics
```

#### 特定指标
```bash
# 搜索缓存命中率
curl http://localhost:8080/actuator/metrics/search.cache.hit.rate

# NLP LLM 回退率
curl http://localhost:8080/actuator/metrics/nlp.llm.fallback.rate

# 平台搜索延迟
curl http://localhost:8080/actuator/metrics/search.platform.latency
```

## 平台能力配置表

### 配置示例（application.yml）

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

### 配置说明

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

### 环境变量覆盖

所有配置都可以通过环境变量覆盖：

```bash
# 禁用拼多多平台
export PLATFORM_PDD_ENABLED=false

# 设置淘宝超时为 3 秒
export PLATFORM_TAOBAO_TIMEOUT_MS=3000

# 设置 eBay 权重为 0.5
export PLATFORM_EBAY_WEIGHT=0.5
```

## 监控仪表板

### Grafana 集成

1. 配置 Prometheus 数据源指向 `http://localhost:8080/actuator/prometheus`
2. 导入推荐的 Dashboard JSON（见 `grafana-dashboard.json`）

### 关键监控面板

1. **搜索性能面板**
   - 搜索总延迟趋势
   - 各平台搜索延迟对比
   - 缓存命中率

2. **NLP 性能面板**
   - NLP 解析延迟
   - LLM 回退率趋势

3. **平台健康面板**
   - 熔断器状态
   - 平台可用性

4. **系统资源面板**
   - 线程池使用率
   - 内存使用情况

## 告警规则建议

### 搜索性能告警
- 搜索总延迟 > 5 秒
- 缓存命中率 < 30%
- 任一平台搜索延迟 > 3 秒

### NLP 性能告警
- NLP 解析延迟 > 2 秒
- LLM 回退率 > 50%

### 平台健康告警
- 熔断器打开次数 > 5 次/小时
- 平台可用性 < 90%

## 日志分析

### 性能日志格式
```
ai_task_start task=recognition metadata={...}
ai_task_finish task=recognition decision=success elapsed_ms=1234
search.cache_hit_rate=0.85
search.platform_latency.pdd=250ms
nlp.llm_fallback_rate=0.15
```

### 日志查询示例
```bash
# 查看搜索延迟日志
grep "search.platform_latency" /var/log/visioncart/app.log

# 查看 NLP 回退日志
grep "nlp.llm_fallback" /var/log/visioncart/app.log

# 查看熔断器状态变化
grep "Circuit" /var/log/visioncart/app.log
```

## 最佳实践

1. **定期监控**：每天检查关键指标趋势
2. **设置基线**：建立正常性能基线，便于识别异常
3. **及时响应**：收到告警后及时排查和处理
4. **容量规划**：根据指标趋势进行容量规划
5. **优化配置**：根据实际性能数据调整平台配置

## 故障排查

### 搜索延迟过高
1. 检查各平台搜索延迟，定位慢平台
2. 检查熔断器状态，是否有平台被熔断
3. 检查网络状况和外部 API 可用性

### 缓存命中率低
1. 检查缓存配置（TTL、大小）
2. 分析搜索请求的重复率
3. 考虑优化缓存键策略

### NLP 回退率高
1. 检查 LLM 服务可用性
2. 分析回退原因（超时、错误等）
3. 考虑优化规则引擎覆盖范围
