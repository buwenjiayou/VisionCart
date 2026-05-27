# 拼多多多多进宝 API 接口文档

> VisionCart 后端集成参考文档 | 多多进宝开放平台 API
>
> 四个接口均为**免费**，需申请「多多客权限包」后调用。

---

## 目录

- [1. 接口总览](#1-接口总览)
- [2. 公共参数](#2-公共参数)
- [3. 签名机制](#3-签名机制)
- [4. 商品搜索](#4-商品搜索)
- [5. 商品推荐](#5-商品推荐)
- [6. 商品详情查询](#6-商品详情查询)
- [7. 推广链接生成](#7-推广链接生成)
- [8. 四接口关系与使用场景](#8-四接口关系与使用场景)
- [9. VisionCart 字段映射](#9-visioncart-字段映射)

---

## 1. 接口总览

| # | 接口名 | method | 用途 | VisionCart 用途 |
|---|--------|--------|------|-----------------|
| 1 | 商品搜索 | `pdd.ddk.goods.search` | 按关键词/类目搜索商品 | **核心比价搜索** |
| 2 | 商品推荐 | `pdd.ddk.goods.recommend.get` | 热销榜/相似推荐/猜你喜欢 | 热门榜单/相似商品 |
| 3 | 商品详情查询 | `pdd.ddk.goods.detail` | 查询单个商品详情 | 商品详情页 |
| 4 | 推广链接生成 | `pdd.ddk.goods.promotion.url.generate` | 生成商品推广链接 | **佣金追踪** |

```
接口调用关系:

  【搜索场景】
  goods.search ──→ 按关键词/类目搜索商品
         │
         ▼
  goods.promotion.url.generate ──→ 生成推广链接（用户点击跳转）

  【推荐场景】
  goods.recommend.get ──→ 热销榜/相似推荐/猜你喜欢（channel_type控制）

  【详情场景】
  goods.detail ──→ 查询单个商品完整信息
```

---

## 2. 公共参数

### 请求地址

| 环境 | 地址 |
|------|------|
| 正式环境 | `https://gw-api.pinduoduo.com/api/router` |

### 公共请求参数

| 名称 | 类型 | 必须 | 说明 |
|------|------|------|------|
| `type` | String | ✅ | API接口名称 |
| `client_id` | String | ✅ | 分配给应用的AppKey |
| `timestamp` | String | ✅ | 时间戳，秒级 |
| `access_token` | String | ❌ | 用户授权token（部分接口需要） |
| `sign` | String | ✅ | 签名结果 |
| `data_type` | String | ❌ | 响应格式，默认JSON |
| `version` | String | ❌ | API版本，默认V1 |

### 公共响应参数

| 名称 | 类型 | 说明 |
|------|------|------|
| `request_id` | String | 请求唯一标识 |
| `error_response` | Object | 错误响应根节点 |
| `error_code` | Number | 错误码 |
| `error_msg` | String | 错误信息 |
| `sub_code` | String | 子错误码 |
| `sub_msg` | String | 子错误信息 |

---

## 3. 签名机制

### 签名步骤

```
1. 将所有请求参数（除 sign 本身）按 key 的字母升序排列
2. 拼接成 key1value1key2value2... 的字符串
3. 在字符串首尾加上 clientSecret: secret + 排序字符串 + secret
4. 对整个字符串做 MD5，取32位大写 hex
```

### 示例

```java
PopClient client = new PopHttpClient(clientId, clientSecret);
PddDdkGoodsSearchRequest request = new PddDdkGoodsSearchRequest();
request.setKeyword("女装");
request.setPid("your_pid");
PddDdkGoodsSearchResponse response = client.syncInvoke(request);
```

---

## 4. 商品搜索

> 按关键词、类目、价格、佣金等条件搜索拼多多商品。

- **method**: `pdd.ddk.goods.search`
- **权限包**: 多多客权限包

### 4.1 请求参数

#### 核心参数

| 名称 | 类型 | 必须 | 示例 | 说明 |
|------|------|------|------|------|
| `pid` | String | ✅ | `your_pid` | 推广位ID |
| `keyword` | String | ❌ | `女装` | 搜索关键词，支持goods_id、拼多多链接、进宝长链/短链 |
| `opt_id` | Long | ❌ | `4` | 商品标签类目ID（见类目映射表） |
| `cat_id` | Long | ❌ | `123` | 商品类目ID |
| `page` | Integer | ❌ | `1` | 页码，默认1 |
| `page_size` | Integer | ❌ | `100` | 每页数量，默认100 |

#### 筛选参数

| 名称 | 类型 | 必须 | 示例 | 说明 |
|------|------|------|------|------|
| `with_coupon` | Boolean | ❌ | `true` | 是否只返回有券商品 |
| `is_brand_goods` | Boolean | ❌ | `true` | 是否品牌商品 |
| `merchant_type` | Integer | ❌ | `3` | 店铺类型（见下表） |
| `merchant_type_list` | Integer[] | ❌ | `[3,4]` | 店铺类型数组 |
| `activity_tags` | Integer[] | ❌ | `[4,7]` | 活动标记（见下表） |
| `goods_sign_list` | String[] | ❌ | `["xxx"]` | 商品goodsSign列表 |
| `goods_img_type` | Integer | ❌ | `1` | 主图类型：1场景图 2白底图 |

**店铺类型 merchant_type：**

| 值 | 说明 |
|----|------|
| 1 | 个人 |
| 2 | 企业 |
| 3 | 旗舰店 |
| 4 | 专卖店 |
| 5 | 专营店 |
| 6 | 普通店 |

**活动标记 activity_tags：**

| 值 | 说明 |
|----|------|
| 4 | 秒杀 |
| 7 | 百亿补贴 |
| 24 | 品牌高佣 |
| 31 | 品牌黑标 |

#### 范围筛选 range_list

| 名称 | 类型 | 说明 |
|------|------|------|
| `range_id` | Integer | 范围类型（见下表） |
| `range_from` | Long | 区间开始值 |
| `range_to` | Long | 区间结束值 |

**range_id 范围类型：**

| 值 | 说明 |
|----|------|
| 0 | 最小成团价 |
| 1 | 券后价 |
| 2 | 佣金比例 |
| 3 | 优惠券价格 |
| 5 | 销量 |
| 6 | 佣金金额 |

#### 排序 sort_type

| 值 | 说明 |
|----|------|
| 0 | 综合排序 |
| 1 | 佣金比率升序 |
| 2 | 佣金比例降序 |
| 3 | 价格升序 |
| 4 | 价格降序 |
| 5 | 销量升序 |
| 6 | 销量降序 |
| 9 | 券后价升序 |
| 10 | 券后价降序 |
| 14 | 佣金金额降序 |

#### 屏蔽参数

| 名称 | 类型 | 说明 |
|------|------|------|
| `block_cat_packages` | Integer[] | 屏蔽类目包：1小程序屏蔽 2虚拟类目 3医疗器械 4处方药 5非处方药 |
| `block_cats` | Integer[] | 自定义屏蔽类目ID，最多20个 |

### 4.2 响应参数

| 名称 | 类型 | 说明 |
|------|------|------|
| `goods_search_response` | Object | 响应根节点 |
| `goods_list` | Array | 商品列表 |
| `list_id` | String | 翻页时必填 |
| `search_id` | String | 搜索ID，生成推广链接时建议填写 |
| `total_count` | Integer | 商品总数 |

**goods_list 商品对象：**

| 名称 | 类型 | 说明 | VisionCart 映射 |
|------|------|------|-----------------|
| `goods_sign` | String | 商品加密ID | `id` (加前缀 `pdd_`) |
| `goods_name` | String | 商品标题 | `title` |
| `goods_image_url` | String | 商品主图 | `imageUrl` |
| `goods_thumbnail_url` | String | 商品缩略图 | — |
| `goods_desc` | String | 商品描述 | — |
| `min_group_price` | Long | 最小拼团价（分） | `price` |
| `min_normal_price` | Long | 最小单买价（分） | `originalPrice` |
| `promotion_rate` | Long | 佣金比例（千分比） | — |
| `sales_tip` | String | 已售卖件数 | `sales` |
| `mall_name` | String | 店铺名称 | `shopName` |
| `mall_id` | Long | 店铺ID | — |
| `merchant_type` | Integer | 店铺类型 | `selfOperated` (3=旗舰店) |
| `brand_name` | String | 品牌名称 | — |
| `opt_name` | String | 商品标签名 | — |
| `opt_id` | Long | 商品标签ID | — |
| `cat_ids` | Long[] | 商品类目ID列表 | — |

**优惠券信息：**

| 名称 | 类型 | 说明 |
|------|------|------|
| `has_coupon` | Boolean | 是否有优惠券 |
| `coupon_discount` | Long | 优惠券面额（分） |
| `coupon_min_order_amount` | Long | 优惠券门槛（分） |
| `coupon_remain_quantity` | Long | 优惠券剩余数量 |
| `coupon_total_quantity` | Long | 优惠券总数量 |
| `coupon_start_time` | Long | 生效时间（Unix时间戳） |
| `coupon_end_time` | Long | 失效时间（Unix时间戳） |

**店铺优惠券：**

| 名称 | 类型 | 说明 |
|------|------|------|
| `has_mall_coupon` | Boolean | 是否有店铺券 |
| `mall_coupon_discount_pct` | Integer | 店铺券折扣 |
| `mall_coupon_min_order_amount` | Integer | 最小使用金额 |
| `mall_coupon_remain_quantity` | Long | 店铺券余量 |

**店铺评分：**

| 名称 | 类型 | 说明 |
|------|------|------|
| `desc_txt` | String | 描述分 |
| `lgst_txt` | String | 物流分 |
| `serv_txt` | String | 服务分 |

**活动信息：**

| 名称 | 类型 | 说明 |
|------|------|------|
| `activity_tags` | Integer[] | 活动标记数组 |
| `activity_type` | Integer | 活动类型 |
| `activity_promotion_rate` | Long | 活动佣金比例（千分比） |

**服务标签 service_tags：**

| 值 | 说明 |
|----|------|
| 1 | 全场包邮 |
| 2 | 七天退换 |
| 3 | 退货包运费 |
| 11 | 闪电退款 |
| 12 | 24小时发货 |
| 13 | 48小时发货 |
| 17 | 顺丰包邮 |
| 24 | 极速退款 |
| 25 | 品质保障 |

**优惠标签 unified_tags：**

- "X元券"
- "比全网低X元"
- "旗舰店"
- "实时热销榜第X名"
- "额外补X元"

### 4.3 响应示例

```json
{
  "goods_search_response": {
    "total_count": 1000,
    "list_id": "xxx",
    "search_id": "xxx",
    "goods_list": [
      {
        "goods_sign": "c9r2omogKFFAc7WBwvbZU1ikIb16_J3CTa8HNN",
        "goods_name": "夏季女装连衣裙",
        "goods_image_url": "https://img.pddpic.com/xxx.jpg",
        "min_group_price": 9900,
        "min_normal_price": 12900,
        "promotion_rate": 200,
        "sales_tip": "1万+",
        "mall_name": "某某旗舰店",
        "merchant_type": 3,
        "has_coupon": true,
        "coupon_discount": 1000,
        "coupon_min_order_amount": 9900,
        "desc_txt": "高",
        "lgst_txt": "高",
        "serv_txt": "高"
      }
    ]
  }
}
```

### 4.4 类目映射表

| opt_id | 类目 |
|--------|------|
| 15 | 百货 |
| 4 | 母婴 |
| 1 | 食品 |
| 14 | 女装 |
| 18 | 电器 |
| 1281 | 鞋包 |
| 1282 | 内衣 |
| 16 | 美妆 |
| 743 | 男装 |
| 13 | 水果 |
| 818 | 家纺 |
| 2478 | 文具 |
| 1451 | 运动 |
| 590 | 虚拟 |
| 2048 | 汽车 |
| 1917 | 家装 |
| 2974 | 家具 |
| 3279 | 医药 |

---

## 5. 商品推荐

> 获取进宝频道推广商品，支持热销榜、相似推荐、猜你喜欢等场景。

- **method**: `pdd.ddk.goods.recommend.get`
- **权限包**: 多多客权限包

### 5.1 请求参数

| 名称 | 类型 | 必须 | 示例 | 说明 |
|------|------|------|------|------|
| `channel_type` | Integer | ❌ | `5` | 频道类型（见下表），默认5 |
| `pid` | String | ❌ | `your_pid` | 推广位ID |
| `cat_id` | Long | ❌ | `20400` | 猜你喜欢场景的商品类目 |
| `goods_sign_list` | String[] | ❌ | `["xxx"]` | 相似商品推荐时必传 |
| `limit` | Integer | ❌ | `20` | 每页数量，默认20 |
| `offset` | Integer | ❌ | `0` | 偏移量，需是limit的整数倍 |
| `list_id` | String | ❌ | `xxx` | 翻页时填写前页返回的值 |
| `activity_tags` | Integer[] | ❌ | `[4,7]` | 活动标记数组 |
| `goods_img_type` | Integer | ❌ | `1` | 主图类型：1场景图 2白底图 |

**channel_type 频道类型：**

| 值 | 说明 |
|----|------|
| 1 | 今日销量榜 |
| 3 | 相似商品推荐（需传goods_sign_list） |
| 4 | 猜你喜欢（和进宝网站精选一致） |
| 5 | 实时热销榜（默认） |
| 6 | 实时收益榜 |

**猜你喜欢类目 cat_id：**

| 值 | 类目 |
|----|------|
| 20100 | 百货 |
| 20200 | 母婴 |
| 20300 | 食品 |
| 20400 | 女装 |
| 20500 | 电器 |
| 20600 | 鞋包 |
| 20700 | 内衣 |
| 20800 | 美妆 |
| 20900 | 男装 |
| 21000 | 水果 |
| 21100 | 家纺 |
| 21200 | 文具 |
| 21300 | 运动 |
| 21400 | 虚拟 |
| 21500 | 汽车 |
| 21600 | 家装 |
| 21700 | 家具 |
| 21800 | 医药 |

### 5.2 响应参数

| 名称 | 类型 | 说明 |
|------|------|------|
| `goods_basic_detail_response` | Object | 响应根节点 |
| `list` | Array | 商品列表 |
| `list_id` | String | 翻页时必填 |
| `search_id` | String | 搜索ID |
| `total` | Integer | 商品总数 |

**list 商品对象：**

字段与 `goods.search` 基本一致，额外字段：

| 名称 | 类型 | 说明 |
|------|------|------|
| `realtime_sales_tip` | String | 近1小时实时销量（仅实时热销榜） |
| `qr_code_image_url` | String | 二维码主图 |
| `share_desc` | String | 分享描述 |

### 5.3 响应示例

```json
{
  "goods_basic_detail_response": {
    "total": 100,
    "list_id": "xxx",
    "search_id": "xxx",
    "list": [
      {
        "goods_sign": "xxx",
        "goods_name": "热销商品",
        "goods_image_url": "https://img.pddpic.com/xxx.jpg",
        "min_group_price": 5900,
        "promotion_rate": 300,
        "sales_tip": "10万+",
        "realtime_sales_tip": "500+"
      }
    ]
  }
}
```

---

## 6. 商品详情查询

> 查询单个商品的详细信息，包含SKU、轮播图、视频、素材等。

- **method**: `pdd.ddk.goods.detail`
- **权限包**: 多多客权限包

### 6.1 请求参数

| 名称 | 类型 | 必须 | 示例 | 说明 |
|------|------|------|------|------|
| `goods_sign` | String | ❌ | `xxx` | 商品加密ID |
| `pid` | String | ❌ | `your_pid` | 推广位ID |
| `search_id` | String | ❌ | `xxx` | 搜索ID，建议填写提高收益 |
| `goods_img_type` | Integer | ❌ | `1` | 主图类型：1场景图 2白底图 |
| `need_sku_info` | Boolean | ❌ | `true` | 是否获取SKU信息（需额外权限） |
| `zs_duo_id` | Long | ❌ | `123` | 招商多多客ID |

### 6.2 响应参数

| 名称 | 类型 | 说明 |
|------|------|------|
| `goods_detail_response` | Object | 响应根节点 |
| `goods_details` | Array | 商品详情列表 |

**goods_details 商品详情对象：**

基础字段与 `goods.search` 一致，额外字段：

| 名称 | 类型 | 说明 |
|------|------|------|
| `goods_gallery_urls` | String[] | 商品轮播图 |
| `video_urls` | String[] | 商品视频URL |
| `mall_img_url` | String | 店铺Logo |
| `material_list` | Array | 商品素材列表 |

**material_list 素材对象：**

| 名称 | 类型 | 说明 |
|------|------|------|
| `id` | String | 素材ID |
| `type` | Integer | 素材类型：1图文 2视频 |
| `image_list` | String[] | 图片列表 |
| `text_list` | String[] | 文字列表 |
| `video_url` | String | 视频URL |
| `thumbnail_url` | String | 视频缩略图 |

**sku_list SKU对象：**

| 名称 | 类型 | 说明 |
|------|------|------|
| `is_onsale` | Integer | 上下架状态：1上架 0下架 |
| `min_group_price` | Long | 最小成团价（分） |
| `sku_thumb_url` | String | SKU预览图 |
| `sku_id_code` | String | SKU加密ID |
| `spec_list` | Array | 规格列表 |

**spec_list 规格对象：**

| 名称 | 类型 | 说明 |
|------|------|------|
| `spec_id` | Long | 规格ID |
| `spec_value` | String | 规格名称（如"XX款"） |
| `parent_spec_id` | Long | 父规格ID |
| `parent_spec_value` | String | 父规格名称（如"型号"） |
| `note` | String | 规格备注 |

### 6.3 响应示例

```json
{
  "goods_detail_response": {
    "goods_details": [
      {
        "goods_sign": "xxx",
        "goods_name": "商品详情",
        "goods_image_url": "https://img.pddpic.com/xxx.jpg",
        "goods_gallery_urls": [
          "https://img.pddpic.com/1.jpg",
          "https://img.pddpic.com/2.jpg"
        ],
        "video_urls": [
          "https://video.pddpic.com/xxx.mp4"
        ],
        "min_group_price": 9900,
        "promotion_rate": 200,
        "sales_tip": "1万+",
        "mall_name": "某某旗舰店",
        "mall_img_url": "https://img.pddpic.com/shop.jpg",
        "sku_list": [
          {
            "is_onsale": 1,
            "min_group_price": 9900,
            "sku_thumb_url": "https://img.pddpic.com/sku.jpg",
            "spec_list": [
              {
                "spec_id": 1,
                "spec_value": "白色",
                "parent_spec_value": "颜色"
              }
            ]
          }
        ],
        "material_list": [
          {
            "id": "xxx",
            "type": 1,
            "image_list": ["https://img.pddpic.com/1.jpg"],
            "text_list": ["推荐理由"]
          }
        ]
      }
    ]
  }
}
```

---

## 7. 推广链接生成

> 将商品goodsSign转换为带推广参数的链接，用于追踪佣金。

- **method**: `pdd.ddk.goods.promotion.url.generate`
- **权限包**: 多多客权限包

### 7.1 接口说明

**推广链接类型：**

| 类型 | 说明 | 使用场景 |
|------|------|----------|
| 普通链接 | 微信内环境使用 | 微信分享 |
| 唤起APP链接 | 非微信环境，拉起拼多多APP | 浏览器/APP内跳转 |

**拼团类型：**

| 类型 | 说明 |
|------|------|
| 单人团 | 用户直接用拼团价购买，无需拼团 |
| 多人团 | 用户开团分享给好友参团，推手获得双份佣金 |

### 7.2 请求参数

#### 核心参数

| 名称 | 类型 | 必须 | 示例 | 说明 |
|------|------|------|------|------|
| `p_id` | String | ✅ | `your_pid` | 推广位ID |
| `goods_sign_list` | String[] | ❌ | `["xxx"]` | 商品goodsSign列表，支持批量生链 |
| `search_id` | String | ❌ | `xxx` | 搜索ID，建议填写提高收益 |

#### 链接类型参数

| 名称 | 类型 | 必须 | 示例 | 说明 |
|------|------|------|------|------|
| `generate_short_url` | Boolean | ❌ | `true` | 是否生成短链接 |
| `generate_schema_url` | Boolean | ❌ | `true` | 是否返回schema URL（唤起APP） |
| `generate_we_app` | Boolean | ❌ | `true` | 是否生成微信小程序推广信息 |
| `generate_qq_app` | Boolean | ❌ | `false` | 是否生成QQ小程序 |
| `generate_short_link` | Boolean | ❌ | `true` | 获取微信ShortLink链接 |
| `generate_weixin_code` | Boolean | ❌ | `true` | 获取微信小程序码 |

#### 拼团参数

| 名称 | 类型 | 必须 | 示例 | 说明 |
|------|------|------|------|------|
| `multi_group` | Boolean | ❌ | `false` | true=多人团 false=单人团（默认） |

#### 礼金参数

| 名称 | 类型 | 必须 | 示例 | 说明 |
|------|------|------|------|------|
| `cash_gift_id` | Long | ❌ | `123` | 多多礼金ID |
| `cash_gift_name` | String | ❌ | `专属福利` | 礼金标题，不超过12字 |

#### 其他参数

| 名称 | 类型 | 必须 | 示例 | 说明 |
|------|------|------|------|------|
| `custom_parameters` | String | ❌ | `{"uid":"111"}` | 自定义参数，最长64字节 |
| `material_id` | String | ❌ | `xxx` | 素材ID |
| `generate_authority_url` | Boolean | ❌ | `false` | 是否生成带授权的单品链接 |
| `generate_mall_collect_coupon` | Boolean | ❌ | `false` | 是否生成店铺收藏券推广链接 |
| `zs_duo_id` | Long | ❌ | `123` | 招商多多客ID |

#### 高级参数 goods_gen_url_param_list

用于拼接特殊参数（如SKU）：

| 名称 | 类型 | 说明 |
|------|------|------|
| `goods_sign` | String | 商品goodsSign |
| `sku_id_list` | Long[] | SKU ID列表（需SKU权限） |
| `sku_id_code_list` | String[] | SKU ID密文列表 |

### 7.3 响应参数

| 名称 | 类型 | 说明 |
|------|------|------|
| `goods_promotion_url_generate_response` | Object | 响应根节点 |
| `goods_promotion_url_list` | Array | 推广链接列表 |

**goods_promotion_url_list 对象：**

| 名称 | 类型 | 说明 |
|------|------|------|
| `url` | String | 普通长链（微信环境拉起小程序） |
| `short_url` | String | 短链接 |
| `mobile_url` | String | 移动端长链（浏览器拉起APP） |
| `mobile_short_url` | String | 移动端短链接 |
| `schema_url` | String | Schema URL（唤起拼多多APP） |
| `tz_schema_url` | String | 唤起多多团长APP |
| `weixin_short_link` | String | 微信小程序短链 |
| `weixin_code` | String | 微信小程序码 |

**we_app_info 微信小程序信息：**

| 名称 | 类型 | 说明 |
|------|------|------|
| `app_id` | String | 小程序ID |
| `page_path` | String | 小程序path |
| `title` | String | 小程序标题 |
| `desc` | String | 描述 |
| `banner_url` | String | Banner图 |
| `user_name` | String | 用户名 |
| `source_display_name` | String | 来源名 |
| `we_app_icon_url` | String | 小程序图标 |

**qq_app_info QQ小程序信息：**

结构同 `we_app_info`。

### 7.4 响应示例

```json
{
  "goods_promotion_url_generate_response": {
    "goods_promotion_url_list": [
      {
        "url": "https://mobile.yangkeduo.com/...",
        "short_url": "https://p.pinduoduo.com/xxx",
        "mobile_url": "https://mobile.yangkeduo.com/...",
        "mobile_short_url": "https://p.pinduoduo.com/xxx",
        "schema_url": "pinduoduo://...",
        "weixin_short_link": "#小程序://拼多多/xxx",
        "weixin_code": "https://img.pddpic.com/xxx.jpg",
        "we_app_info": {
          "app_id": "wx325405a0b3f96daa",
          "page_path": "pages/goods/goods.html?goods_id=xxx",
          "title": "商品标题",
          "desc": "商品描述"
        }
      }
    ]
  }
}
```

### 7.5 使用建议

| 场景 | 推荐链接类型 | 参数设置 |
|------|-------------|----------|
| 微信分享 | 小程序短链 | `generate_we_app=true`, `generate_short_link=true` |
| APP内跳转 | Schema URL | `generate_schema_url=true` |
| 浏览器跳转 | 移动端链接 | `mobile_url` 或 `mobile_short_url` |
| 朋友圈海报 | 小程序码 | `generate_weixin_code=true` |

---

## 8. 四接口关系与使用场景

### 调用流程

```
┌─────────────────────────────────────────────────────────────────┐
│                    VisionCart 使用场景                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  场景A: 关键词搜索比价（现有功能）                                 │
│  ┌─────────────────────────────────────────┐                   │
│  │  接口4: goods.search                      │                   │
│  │  入参: keyword + pid + 筛选条件           │                   │
│  │  出参: 商品列表 + 佣金 + 优惠券            │                   │
│  └─────────────────────────────────────────┘                   │
│                                                                 │
│  场景B: 热门榜单/相似推荐（新增）                                  │
│  ┌─────────────────────────────────────────┐                   │
│  │  接口5: goods.recommend.get               │                   │
│  │  入参: channel_type=5(热销榜)             │                   │
│  │  入参: channel_type=3 + goods_sign(相似)  │                   │
│  └─────────────────────────────────────────┘                   │
│                                                                 │
│  场景C: 商品详情页（新增）                                        │
│  ┌─────────────────────────────────────────┐                   │
│  │  接口6: goods.detail                      │                   │
│  │  入参: goods_sign                         │                   │
│  │  出参: 完整详情 + SKU + 轮播图 + 视频      │                   │
│  └─────────────────────────────────────────┘                   │
│                                                                 │
│  场景D: 生成推广链接（核心）                                       │
│  ┌─────────────────────────────────────────┐                   │
│  │  接口7: goods.promotion.url.generate      │                   │
│  │  入参: goods_sign_list + p_id             │                   │
│  │  出参: 推广链接（用户点击跳转，追踪佣金）   │                   │
│  └─────────────────────────────────────────┘                   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### VisionCart 集成建议

| 场景 | 接口 | 建议端点 | 缓存策略 |
|------|------|----------|----------|
| 搜索比价 | 接口4 | `POST /api/v1/search/products`（已有） | 5分钟 Redis |
| 热销榜单 | 接口5 | `GET /api/v1/search/hot`（新增） | 10分钟 Redis |
| 相似推荐 | 接口5 | `GET /api/v1/search/similar/{goodsSign}`（新增） | 30分钟 Redis |
| 商品详情 | 接口6 | `GET /api/v1/products/{goodsSign}`（新增） | 10分钟 Redis |
| 推广链接 | 接口7 | `POST /api/v1/promotion/links`（新增） | 1小时 Redis |

---

## 9. VisionCart 字段映射

### 拼多多响应 → ProductCard 映射

```java
// PddSearchService.java 映射逻辑
ProductCard card = new ProductCard(
    "pdd_" + goodsSign,                // id: 加平台前缀
    goodsName,                          // title: 商品标题
    goodsImageUrl,                      // imageUrl: 商品主图
    new BigDecimal(minGroupPrice / 100), // price: 拼团价（分转元）
    new BigDecimal(minNormalPrice / 100), // originalPrice: 单买价
    "拼多多",                           // platform: 固定值
    merchantType == 3,                  // selfOperated: 3=旗舰店
    mallName,                           // shopName: 店铺名
    0.0,                                // rating: 拼多多不提供评分
    parseSalesTip(salesTip),            // sales: 解析"1万+"为数值
    0.0,                                // similarity: 搜索场景无相似度
    tags,                               // tags: 有券/旗舰店/包邮等
    ""                                  // detailUrl: 需要另外生成推广链接
);
```

### 可扩展字段（当前未使用）

| 拼多多字段 | 用途 | 建议 |
|------------|------|------|
| `promotion_rate` | 佣金比例（千分比） | 可展示"佣金X%" |
| `coupon_discount` | 优惠券金额 | 可展示"券后价" |
| `desc_txt/lgst_txt/serv_txt` | 店铺评分 | 可展示店铺DSR |
| `service_tags` | 服务标签 | 可展示"包邮/闪电退款"等 |
| `unified_tags` | 优惠标签 | 可展示"实时热销榜第X名" |
| `activity_tags` | 活动标记 | 可展示"百亿补贴/秒杀" |
| `brand_name` | 品牌名 | 可用于品牌筛选 |

---

## 附录

### A. 错误码

| 错误码 | 说明 | 解决方案 |
|--------|------|----------|
| 10000 | 参数错误 | 检查请求参数 |
| 10001 | 公共参数错误 | 检查公共参数 |
| 10016 | clientId不正确 | 检查client_id |
| 20004 | 签名校验失败 | 检查签名算法 |
| 20005 | IP无权访问 | 加入IP白名单 |
| 20031 | 没有授权 | 检查权限包 |
| 21001 | 请求参数错误 | 检查业务参数 |
| 52101 | 被限流 | 稍后重试 |
| 70031 | 调用过于频繁 | 调整调用频率 |

### B. 配置项

```yaml
# application.yml
visioncart:
  pdd:
    api-url: https://gw-api.pinduoduo.com/api/router
    client-id: ${PDD_CLIENT_ID}
    client-secret: ${PDD_CLIENT_SECRET}
    pid: ${PDD_PID}
```

### C. 参考链接

- 多多进宝开放平台: https://jinbao.pinduoduo.com
- 多多进宝推广位管理: https://jinbao.pinduoduo.com/promotion
- goodsSign使用说明: https://jinbao.pinduoduo.com/qa-system?questionId=252
