# 阿里云 AI 服务配置文档

> VisionCart 阿里云百炼（Qwen-VL）API 配置

---

## 配置概览

| 服务 | 用途 | 模型 |
|------|------|------|
| 商品检测与粗识别 | 检测商品位置、裁剪、识别类目+品牌 | Qwen3-VL-Flash |
| 属性提取 | 细粒度商品属性结构化提取 | Qwen3-VL-Plus |

---

## 密钥配置

```yaml
# application.yml 或环境变量
aliyun:
  bailian:
    api-key: ${ALIYUN_BAILIAN_API_KEY}  # 阿里云百炼 API Key
    base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
```

```bash
# 环境变量方式（推荐）
export ALIYUN_BAILIAN_API_KEY=replace-with-your-key
```

---

## 模型配置

```yaml
visioncart:
  ai:
    # Step 1: 商品检测 + 粗识别（快、便宜）
    qwen-flash-model: qwen3-vl-flash

    # Step 2: 属性提取（准、结构化）
    qwen-plus-model: qwen3-vl-plus
  recognition:
    # 多商品检测阈值
    min-detection-confidence: 0.7
    max-products: 5  # 最多检测5个商品
```

---

## 调用示例

### 1. 单商品识别流程

```java
@Service
public class ProductRecognitionService {
    
    @Value("${aliyun.bailian.api-key}")
    private String apiKey;
    
    @Value("${visioncart.ai.recognition.flash-model}")
    private String flashModel;
    
    @Value("${visioncart.ai.recognition.plus-model}")
    private String plusModel;
    
    private final OpenAIClient client = OpenAIClient.builder()
        .apiKey(apiKey)
        .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
        .build();
    
    /**
     * Step 1: Flash 检测 + 粗识别
     */
    public DetectionResult detectProducts(MultipartFile image) {
        String prompt = """
            分析这张图片：
            1. 检测所有商品位置，输出 bounding box
            2. 对每个商品：识别类目、品牌
            3. 返回最大/最清晰的商品区域
            
            输出JSON格式：
            {
              "products": [{
                "bbox": [x1, y1, x2, y2],
                "category": "类目",
                "brand": "品牌或null",
                "confidence": 0.95
              }]
            }
            """;
        
        ChatCompletionRequest request = ChatCompletionRequest.builder()
            .model(flashModel)
            .message(UserMessage.builder()
                .content(Arrays.asList(
                    ImageMessageContent.builder()
                        .imageUrl(ImageUrl.builder()
                            .url("data:image/jpeg;base64," + base64Encode(image))
                            .build())
                        .build(),
                    TextMessageContent.builder().text(prompt).build()
                ))
                .build())
            .build();
        
        return parseDetectionResult(client.chatCompletion(request));
    }
    
    /**
     * Step 2: Plus 属性提取
     */
    public RecognitionResult extractAttributes(
            byte[] cropImage, 
            String category, 
            String brand) {
        
        String prompt = buildAttributePrompt(category, brand);
        
        ChatCompletionRequest request = ChatCompletionRequest.builder()
            .model(plusModel)
            .message(UserMessage.builder()
                .content(Arrays.asList(
                    ImageMessageContent.builder()
                        .imageUrl(ImageUrl.builder()
                            .url("data:image/jpeg;base64," + base64Encode(cropImage))
                            .build())
                        .build(),
                    TextMessageContent.builder().text(prompt).build()
                ))
                .build())
            .build();
        
        return parseRecognitionResult(client.chatCompletion(request));
    }
}
```

### 2. 多商品处理

```java
public RecognitionResponse recognize(MultipartFile image) {
    // Step 1: 检测
    DetectionResult detection = detectProducts(image);
    
    if (detection.getProducts().size() == 1) {
        // 单商品：直接识别
        Product product = detection.getProducts().get(0);
        byte[] crop = cropImage(image, product.getBbox());
        RecognitionResult result = extractAttributes(
            crop, 
            product.getCategory(), 
            product.getBrand()
        );
        return RecognitionResponse.single(result);
    }
    
    // 多商品：返回预览，让用户选择
    return RecognitionResponse.multiSelect(detection.getProducts());
}
```

---

## Prompt 模板

### Flash 检测 Prompt

```
你是一个电商商品检测专家。请分析这张图片：

任务：
1. 检测图中所有商品，输出每个商品的 bounding box [x1, y1, x2, y2]
2. 对每个商品识别：
   - category: 商品类目（如：连衣裙、运动鞋、手机、包包）
   - brand: 品牌名称（如识别不出填 null）
   - confidence: 置信度 0-1
3. 按商品面积从大到小排序

输出严格的JSON格式：
{
  "products": [
    {
      "bbox": [100, 200, 400, 600],
      "category": "连衣裙",
      "brand": "ZARA",
      "confidence": 0.95
    }
  ],
  "total": 1
}
```

### Plus 属性提取 Prompt

```
你正在分析一个【{category}】商品{brand_hint}。

请仔细观察图片，提取以下属性，输出JSON格式：

{
  "category": "确认类目",
  "brand": "确认品牌",
  "attributes": {类目特定属性},
  "style": "风格描述（如：休闲、商务、甜美）",
  "quality_score": 4.5,
  "confidence": 0.92
}

类目属性规范：
- 连衣裙: {color, size, material, sleeve_length, skirt_length, pattern, neckline}
- 运动鞋: {color, size, upper_material, sole_material, sport_type, closure_type}
- 手机: {brand, model, color, storage, screen_size}
- 包包: {color, size, material, style, closure_type}
```

---

## 成本参考

| 模型 | 输入价格 | 输出价格 | 单次识别成本 |
|------|---------|---------|-------------|
| Qwen3-VL-Flash | ¥0.5/1M tokens | ¥1.0/1M tokens | ~¥0.001 |
| Qwen3-VL-Plus | ¥2.0/1M tokens | ¥4.0/1M tokens | ~¥0.005 |
| **总计** | - | - | **~¥0.006/商品** |

---

## 错误处理

```java
public enum RecognitionError {
    NO_PRODUCT_DETECTED("未检测到商品"),
    LOW_CONFIDENCE("置信度过低"),
    API_ERROR("阿里云API调用失败"),
    PARSE_ERROR("结果解析失败"),
    MULTI_PRODUCTS_NEED_SELECT("多商品需用户选择");
    
    private final String message;
}
```

---

## 相关文档

- [阿里云百炼控制台](https://bailian.console.aliyun.com/)
- [Qwen3-VL 官方文档](https://help.aliyun.com/zh/model-studio/developer-reference/qwen3-vl)
- [API 定价](https://www.aliyun.com/product/bailian)

---

## 安全提示

⚠️ **API Key 安全**：
- 不要将 API Key 提交到代码仓库
- 使用环境变量或密钥管理服务
- 定期轮换密钥
