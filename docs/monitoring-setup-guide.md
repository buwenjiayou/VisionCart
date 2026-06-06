# VisionCart 监控设置指南

## 概述

本指南将帮助您设置 VisionCart 的性能监控系统，包括 Prometheus、Grafana 和日志分析。

## 1. Prometheus 设置

### 1.1 安装 Prometheus

```bash
# 下载 Prometheus
wget https://github.com/prometheus/prometheus/releases/download/v2.45.0/prometheus-2.45.0.windows-amd64.tar.gz

# 解压
tar -xzf prometheus-2.45.0.windows-amd64.tar.gz
cd prometheus-2.45.0.windows-amd64
```

### 1.2 配置 Prometheus

创建 `prometheus.yml` 配置文件：

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'visioncart'
    static_configs:
      - targets: ['localhost:8080']
    metrics_path: '/actuator/prometheus'
    scheme: 'http'
```

### 1.3 启动 Prometheus

```bash
# 启动 Prometheus
./prometheus --config.file=prometheus.yml

# 访问 Prometheus UI
# http://localhost:9090
```

## 2. Grafana 设置

### 2.1 安装 Grafana

```bash
# 下载 Grafana
wget https://dl.grafana.com/oss/release/grafana-10.0.0.windows-amd64.zip

# 解压
unzip grafana-10.0.0.windows-amd64.zip
cd grafana-10.0.0
```

### 2.2 启动 Grafana

```bash
# 启动 Grafana
./bin/grafana-server.exe

# 访问 Grafana UI
# http://localhost:3000
# 默认用户名/密码: admin/admin
```

### 2.3 配置数据源

1. 登录 Grafana
2. 进入 **Configuration** > **Data Sources**
3. 点击 **Add data source**
4. 选择 **Prometheus**
5. 配置：
   - **Name**: VisionCart Prometheus
   - **URL**: http://localhost:9090
   - **Access**: Server (default)
6. 点击 **Save & Test**

### 2.4 导入仪表板

1. 进入 **Dashboards** > **Import**
2. 点击 **Upload JSON file**
3. 选择 `docs/grafana-dashboard.json`
4. 点击 **Import**

## 3. 监控面板说明

### 3.1 搜索性能面板

#### 搜索总延迟趋势
- **指标**: `histogram_quantile(0.95, rate(search_total_latency_seconds_bucket[5m]))`
- **说明**: 显示搜索请求的 95% 延迟趋势
- **告警阈值**: > 5 秒

#### 各平台搜索延迟对比
- **指标**: `histogram_quantile(0.95, rate(search_platform_latency_seconds_bucket[5m]))`
- **说明**: 对比各平台（拼多多、淘宝、eBay）的搜索延迟
- **告警阈值**: > 3 秒

#### 缓存命中率
- **指标**: `search_cache_hit_rate`
- **说明**: 搜索缓存的命中率百分比
- **告警阈值**: < 30%

### 3.2 NLP 性能面板

#### NLP 解析延迟
- **指标**: `histogram_quantile(0.95, rate(nlp_parse_latency_seconds_bucket[5m]))`
- **说明**: NLP 解析请求的 95% 延迟趋势
- **告警阈值**: > 2 秒

#### LLM 回退率趋势
- **指标**: `nlp_llm_fallback_rate`
- **说明**: NLP LLM 回退的百分比趋势
- **告警阈值**: > 50%

### 3.3 平台健康面板

#### 熔断器状态
- **指标**: `platform_circuit_open_count`
- **说明**: 各平台熔断器打开次数
- **告警阈值**: > 5 次/小时

#### 平台可用性
- **指标**: `up{job="visioncart"}`
- **说明**: VisionCart 服务可用性
- **告警阈值**: < 99%

### 3.4 系统资源面板

#### 线程池使用率
- **指标**: `jvm_threads_live_threads`
- **说明**: JVM 活跃线程数
- **告警阈值**: > 200

#### 内存使用情况
- **指标**: `jvm_memory_used_bytes`
- **说明**: JVM 内存使用量
- **告警阈值**: > 80% 最大内存

## 4. 告警规则配置

### 4.1 创建告警规则文件

创建 `visioncart-alerts.yml`：

```yaml
groups:
  - name: visioncart_alerts
    rules:
      # 搜索性能告警
      - alert: HighSearchLatency
        expr: histogram_quantile(0.95, rate(search_total_latency_seconds_bucket[5m])) > 5
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "搜索延迟过高"
          description: "搜索请求的 95% 延迟超过 5 秒，当前值: {{ $value }}"

      - alert: LowCacheHitRate
        expr: search_cache_hit_rate < 0.3
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "缓存命中率过低"
          description: "搜索缓存命中率低于 30%，当前值: {{ $value }}"

      # NLP 性能告警
      - alert: HighNlpLatency
        expr: histogram_quantile(0.95, rate(nlp_parse_latency_seconds_bucket[5m])) > 2
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "NLP 延迟过高"
          description: "NLP 解析请求的 95% 延迟超过 2 秒，当前值: {{ $value }}"

      - alert: HighLlmFallbackRate
        expr: nlp_llm_fallback_rate > 0.5
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "LLM 回退率过高"
          description: "NLP LLM 回退率超过 50%，当前值: {{ $value }}"

      # 平台健康告警
      - alert: CircuitBreakerOpen
        expr: increase(platform_circuit_open_count[1h]) > 5
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "熔断器频繁打开"
          description: "平台 {{ $labels.platform }} 熔断器在过去 1 小时内打开超过 5 次"

      # 系统资源告警
      - alert: HighThreadCount
        expr: jvm_threads_live_threads > 200
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "线程数过高"
          description: "JVM 活跃线程数超过 200，当前值: {{ $value }}"

      - alert: HighMemoryUsage
        expr: (jvm_memory_used_bytes / jvm_memory_max_bytes) > 0.8
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "内存使用率过高"
          description: "JVM 内存使用率超过 80%，当前值: {{ $value | humanizePercentage }}"
```

### 4.2 配置 Prometheus 告警

在 `prometheus.yml` 中添加告警配置：

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

rule_files:
  - "visioncart-alerts.yml"

scrape_configs:
  - job_name: 'visioncart'
    static_configs:
      - targets: ['localhost:8080']
    metrics_path: '/actuator/prometheus'
    scheme: 'http'
```

## 5. 日志分析设置

### 5.1 ELK Stack 设置（可选）

#### 安装 Elasticsearch

```bash
# 下载 Elasticsearch
wget https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-8.8.0-windows-x86_64.zip

# 解压并启动
unzip elasticsearch-8.8.0-windows-x86_64.zip
cd elasticsearch-8.8.0
./bin/elasticsearch.bat
```

#### 安装 Logstash

```bash
# 下载 Logstash
wget https://artifacts.elastic.co/downloads/logstash/logstash-8.8.0-windows-x86_64.zip

# 解压
unzip logstash-8.8.0-windows-x86_64.zip
cd logstash-8.8.0
```

#### 配置 Logstash

创建 `logstash.conf`：

```input {
  file {
    path => "D:/workspace/VisionCart/backend-stdout.log"
    start_position => "beginning"
    sincedb_path => "NUL"
  }
}

filter {
  grok {
    match => { "message" => "%{TIMESTAMP_ISO8601:timestamp} %{LOGLEVEL:level} %{GREEDYDATA:message}" }
  }
  
  if [message] =~ /search\.platform_latency/ {
    grok {
      match => { "message" => "search\.platform_latency\.\w+: %{NUMBER:latency} ms" }
    }
    mutate {
      convert => { "latency" => "float" }
    }
  }
  
  if [message] =~ /cache_hit_rate/ {
    grok {
      match => { "message" => "Search Cache Hit Rate: %{NUMBER:hit_rate}%" }
    }
    mutate {
      convert => { "hit_rate" => "float" }
    }
  }
}

output {
  elasticsearch {
    hosts => ["localhost:9200"]
    index => "visioncart-logs-%{+YYYY.MM.dd}"
  }
}
```

#### 启动 Logstash

```bash
./bin/logstash -f logstash.conf
```

#### 安装 Kibana

```bash
# 下载 Kibana
wget https://artifacts.elastic.co/downloads/kibana/kibana-8.8.0-windows-x86_64.zip

# 解压并启动
unzip kibana-8.8.0-windows-x86_64.zip
cd kibana-8.8.0
./bin/kibana.bat

# 访问 Kibana UI
# http://localhost:5601
```

### 5.2 简单日志分析脚本

创建 `analyze-logs.py`：

```python
#!/usr/bin/env python3
import re
import sys
from collections import defaultdict

def analyze_log_file(log_file):
    metrics = defaultdict(list)
    
    with open(log_file, 'r', encoding='utf-8') as f:
        for line in f:
            # 提取搜索延迟
            if 'search.platform_latency' in line:
                match = re.search(r'search\.platform_latency\.(\w+): (\d+\.?\d*) ms', line)
                if match:
                    platform = match.group(1)
                    latency = float(match.group(2))
                    metrics[f'platform_latency_{platform}'].append(latency)
            
            # 提取缓存命中率
            if 'Search Cache Hit Rate' in line:
                match = re.search(r'Search Cache Hit Rate: (\d+\.?\d*)%', line)
                if match:
                    metrics['cache_hit_rate'].append(float(match.group(1)))
            
            # 提取 NLP 回退率
            if 'NLP LLM Fallback Rate' in line:
                match = re.search(r'NLP LLM Fallback Rate: (\d+\.?\d*)%', line)
                if match:
                    metrics['nlp_fallback_rate'].append(float(match.group(1)))
            
            # 提取熔断器状态
            if 'Circuit opened for' in line:
                match = re.search(r'Circuit opened for (\w+)', line)
                if match:
                    platform = match.group(1)
                    metrics[f'circuit_open_{platform}'].append(1)
    
    # 打印统计结果
    print("=== VisionCart Log Analysis ===")
    print()
    
    for metric, values in metrics.items():
        if values:
            avg = sum(values) / len(values)
            print(f"{metric}:")
            print(f"  Count: {len(values)}")
            print(f"  Average: {avg:.2f}")
            if len(values) > 1:
                print(f"  Min: {min(values):.2f}")
                print(f"  Max: {max(values):.2f}")
            print()
    
    print("=== End of Analysis ===")

if __name__ == '__main__':
    if len(sys.argv) > 1:
        analyze_log_file(sys.argv[1])
    else:
        print("Usage: python analyze-logs.py <log_file>")
```

## 6. 监控最佳实践

### 6.1 监控频率

| 指标类型 | 监控频率 | 告警阈值 |
|---------|---------|---------|
| 搜索延迟 | 实时 | > 5 秒 |
| 缓存命中率 | 每分钟 | < 30% |
| NLP 延迟 | 实时 | > 2 秒 |
| LLM 回退率 | 每分钟 | > 50% |
| 熔断器状态 | 实时 | > 5 次/小时 |
| 系统资源 | 每分钟 | > 80% |

### 6.2 告警级别

| 级别 | 说明 | 响应时间 |
|------|------|---------|
| **Critical** | 服务不可用、数据丢失风险 | 立即响应 |
| **Warning** | 性能下降、资源紧张 | 1 小时内响应 |
| **Info** | 信息性告警、趋势变化 | 24 小时内响应 |

### 6.3 监控仪表板布局

推荐的 Grafana 仪表板布局：

```
┌─────────────────────────────────────────────────────────────┐
│                    VisionCart Performance Dashboard           │
├─────────────────────────────────────────────────────────────┤
│  搜索延迟趋势    │  平台延迟对比    │  缓存命中率    │  系统状态  │
├─────────────────────────────────────────────────────────────┤
│  NLP 延迟趋势    │  LLM 回退率     │  熔断器状态    │  线程池   │
├─────────────────────────────────────────────────────────────┤
│  识别延迟趋势    │  候选过滤率     │  内存使用      │  CPU 使用  │
└─────────────────────────────────────────────────────────────┘
```

## 7. 故障排查

### 7.1 常见问题

#### 问题 1: Prometheus 无法连接到 VisionCart
**症状**: Prometheus targets 页面显示 VisionCart 为 DOWN
**解决**:
1. 检查 VisionCart 是否启动
2. 检查 Actuator 端点是否启用
3. 检查防火墙设置

#### 问题 2: Grafana 无法显示数据
**症状**: Grafana 图表显示 "No data"
**解决**:
1. 检查 Prometheus 数据源配置
2. 检查 PromQL 查询语法
3. 检查时间范围设置

#### 问题 3: 告警不触发
**症状**: 指标超过阈值但未收到告警
**解决**:
1. 检查告警规则语法
2. 检查告警通知配置
3. 检查告警冷却时间

### 7.2 调试命令

```bash
# 检查 VisionCart Actuator 端点
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/prometheus

# 检查 Prometheus targets
curl http://localhost:9090/api/v1/targets

# 检查 Grafana 数据源
curl http://localhost:3000/api/datasources

# 查看 Prometheus 告警规则
curl http://localhost:9090/api/v1/rules
```

## 8. 性能优化建议

### 8.1 搜索性能优化

1. **增加缓存 TTL**: 将搜索缓存 TTL 从 5 分钟增加到 10 分钟
2. **优化缓存键**: 使用更精确的缓存键减少缓存冲突
3. **并行搜索优化**: 调整线程池大小和超时设置

### 8.2 NLP 性能优化

1. **规则引擎优化**: 增加规则覆盖范围减少 LLM 调用
2. **LLM 缓存**: 缓存常见查询的 LLM 结果
3. **异步处理**: 将非关键 NLP 任务异步化

### 8.3 平台性能优化

1. **超时调整**: 根据实际延迟调整各平台超时设置
2. **权重优化**: 根据平台性能调整权重配置
3. **降级策略**: 优化降级优先级和策略

## 9. 总结

通过本指南，您已经成功设置了 VisionCart 的性能监控系统。现在您可以：

✅ 实时监控关键性能指标
✅ 设置告警规则及时响应问题
✅ 分析日志发现性能瓶颈
✅ 优化系统性能提升用户体验

建议定期检查监控仪表板，及时发现和解决潜在问题，确保 VisionCart 系统的稳定性和高性能。
