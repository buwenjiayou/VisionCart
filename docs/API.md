# VisionCart API 说明

后端默认地址：`http://localhost:8080`。Swagger 地址：`/swagger-ui.html`。

## 通用响应

```json
{
  "code": 200,
  "message": "ok",
  "data": {},
  "trace_id": null
}
```

## POST /api/v1/recognition/analyze

上传图片并返回结构化识别结果。

请求：`multipart/form-data`

- `image`: 图片文件，必填
- `region`: 框选区域，可选，格式 `x1,y1,x2,y2`

返回核心字段：

- `session_id`
- `category.level1/level2/level3`
- `attributes.品牌/颜色/款式/材质`
- `keywords`
- `overall_confidence`

## PUT /api/v1/recognition/attributes

修正识别属性，并重新检索商品。

```json
{
  "session_id": "uuid",
  "attribute": "颜色",
  "old_value": "蓝色",
  "new_value": "深蓝色"
}
```

## POST /api/v1/search/products

根据识别属性和筛选条件检索商品。

```json
{
  "session_id": "uuid",
  "attributes": {
    "品牌": "耐克",
    "颜色": "黑色",
    "款式": "跑鞋",
    "类目": "鞋靴"
  },
  "filter": {
    "price_range": { "min": null, "max": 1000 },
    "platforms": [],
    "self_operated": null,
    "colors": ["黑色"],
    "brands": [],
    "rating_min": 4.8,
    "sort_by": "sales",
    "sort_order": "desc",
    "keyword": null
  },
  "page": 1,
  "page_size": 20,
  "client_type": "app"
}
```

返回：

- `products`: 商品卡片列表
- `platform_stats`: 平台最低价、均价、数量
- `suggestion_cards`: 智能建议卡片

## POST /api/v1/nlp/parse

自然语言转结构化筛选条件。

```json
{
  "session_id": "uuid",
  "user_input": "1000 元以内黑色款，要评价 4.8 分以上，按销量排",
  "context": {
    "product_name": "耐克跑鞋",
    "category": "鞋靴"
  }
}
```

返回：

- `filter`
- `confidence`
- `from_cache`
- `decision`: `cache_hit`、`rule_primary`、`spring_ai_llm`

## GET /api/v1/suggestions/cards

参数：

- `session_id`
- `client_type`: `app` 或 `overlay`

返回当前建议卡片。

## POST /api/v1/suggestions/execute

执行卡片动作。

```json
{
  "session_id": "uuid",
  "action": "sort_by_price_asc",
  "current_products": []
}
```

支持动作：

- `sort_by_price_asc`
- `filter_self_operated`
- `filter_rating_4_8`
- `show_price_history`
- `search_similar_style`

## GET /api/v1/history

返回最近 20 条识物历史。

## POST /api/v1/favorites

添加收藏商品。

```json
{
  "product_id": "pdd_001",
  "session_id": "uuid",
  "platform": "拼多多",
  "title": "耐克黑色跑鞋透气飞织旗舰同款",
  "price": 219,
  "detail_url": "https://example.com/products/pdd_001"
}
```
