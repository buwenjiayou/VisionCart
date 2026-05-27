# 淘宝客 API 接口文档

> VisionCart 后端集成参考文档 | 淘宝开放平台 TOP API
>
> 七个接口均为**免费、不需用户授权**，只需 `app_key` + `adzone_id` 即可调用。

---

## 目录

- [1. 接口总览](#1-接口总览)
- [2. 公共参数](#2-公共参数)
- [3. 签名机制](#3-签名机制)
- [4. 物料搜索升级版（核心）](#4-物料搜索升级版)
- [5. 物料精选升级版](#5-物料精选升级版)
- [6. 物料ID列表查询](#6-物料id列表查询)
- [7. 店铺搜索](#7-店铺搜索)
- [8. 商品详情查询升级版](#8-商品详情查询升级版)
- [9. 推广券详情查询](#9-推广券详情查询)
- [10. 商品详情查询(简版)](#10-商品详情查询简版)
- [11. 七接口关系与使用场景](#11-七接口关系与使用场景)
- [12. VisionCart 字段映射](#12-visioncart-字段映射)

---

## 1. 接口总览

| # | 接口名 | method | 用途 | VisionCart 用途 |
|---|--------|--------|------|-----------------|
| 1 | 物料搜索升级版 | `taobao.tbk.dg.material.optional.upgrade` | 按关键词/类目搜索商品 | **核心比价搜索** |
| 2 | 物料精选升级版 | `taobao.tbk.dg.material.recommend` | 根据物料ID推荐商品 | 热门榜单/促销活动/相似推荐 |
| 3 | 物料ID列表查询 | `taobao.tbk.optimus.tou.material.ids.get` | 获取可选物料分类 | 一次性获取物料ID列表 |
| 4 | 店铺搜索 | `taobao.tbk.shop.get` | 按关键词搜索店铺 | 店铺维度推荐 |
| 5 | 商品详情查询升级版 | `taobao.tbk.item.info.upgrade.get` | 单个商品详情（含佣金/优惠） | 商品详情页 |
| 6 | 推广券详情查询 | `taobao.tbk.coupon.get` | 查询优惠券详情 | 展示券信息 |
| 7 | 商品详情查询(简版) | `taobao.tbk.item.info.get` | 批量商品基础信息 | 快速查询多个商品 |

```
接口调用关系:

  【搜索/推荐场景】
  material.ids.get ──→ 获取物料ID列表（促销/热门/榜单...）
         │
         ▼
  material.recommend ──→ 根据物料ID获取推荐商品

  material.optional.upgrade ──→ 按关键词搜索商品（独立使用）

  shop.get ──→ 按关键词搜索店铺（独立使用）

  【详情查询场景】
  item.info.upgrade.get ──→ 单个商品详情（含佣金、优惠、店铺评分）
  item.info.get ──→ 批量商品基础信息（简版）
  coupon.get ──→ 优惠券详情查询
```

---

## 2. 公共参数

### 请求地址

| 环境 | HTTP | HTTPS |
|------|------|-------|
| 正式 | `http://gw.api.taobao.com/router/rest` | `https://eco.taobao.com/router/rest` |

### 公共请求参数

| 名称 | 类型 | 必须 | 说明 |
|------|------|------|------|
| `method` | String | ✅ | API接口名称 |
| `app_key` | String | ✅ | TOP分配的AppKey |
| `timestamp` | String | ✅ | 时间戳，格式 `yyyy-MM-dd HH:mm:ss`，时区 GMT+8，允许误差10分钟 |
| `v` | String | ✅ | API协议版本，固定 `2.0` |
| `sign_method` | String | ✅ | 签名算法：`hmac` / `md5` / `hmac-sha256` |
| `sign` | String | ✅ | 签名结果（见[签名机制](#3-签名机制)） |
| `format` | String | ❌ | 响应格式，默认xml，推荐 `json` |
| `simplify` | Boolean | ❌ | 精简JSON，仅format=json时有效，默认false |
| `session` | String | ❌ | 用户授权token（本文档三个接口均不需要） |

### 公共响应参数

| 名称 | 类型 | 说明 |
|------|------|------|
| `request_id` | String | 请求唯一标识 |
| `code` | String | 失败错误码 |
| `msg` | String | 失败错误信息 |
| `sub_code` | String | 失败子错误码 |
| `sub_msg` | String | 失败子错误信息 |
| `***_response` | Object | 成功响应根节点（***为API名下划线模式） |

---

## 3. 签名机制

### MD5 签名步骤

```
1. 将所有请求参数（除 sign 本身）按 key 的字母升序排列
2. 拼接成 key1value1key2value2... 的字符串
3. 在字符串首尾加上 AppSecret: secret + 排序字符串 + secret
4. 对整个字符串做 MD5，取32位小写 hex
```

### 示例

```
参数: app_key=12345, method=taobao.tbk.xxx, timestamp=2025-01-01 12:00:00, v=2.0
排序: app_key=12345&method=taobao.tbk.xxx&timestamp=2025-01-01 12:00:00&v=2.0
签名: MD5(secret + "app_key=12345&method=taobao.tbk.xxx&timestamp=2025-01-01 12:00:00&v=2.0" + secret)
```

---

## 4. 物料搜索升级版

> **核心接口** — 按关键词/类目/价格等条件搜索淘宝客商品，支持丰富的筛选和排序。

- **method**: `taobao.tbk.dg.material.optional.upgrade`
- **费用**: 免费
- **授权**: 不需要

### 4.1 请求参数

#### 核心参数

| 名称 | 类型 | 必须 | 示例 | 说明 |
|------|------|------|------|------|
| `adzone_id` | Number | ✅ | `12345678` | 推广位ID（mm_xxx_xxx_12345678 最后一段） |
| `q` | String | ❌ | `女装` | 搜索关键词 |
| `material_id` | Number | ❌ | `80309` | 物料ID，默认80309；消费者投放用17004 |
| `page_no` | Number | ❌ | `1` | 页码，默认1 |
| `page_size` | Number | ❌ | `20` | 每页数量，默认20，范围1~100 |

#### 价格筛选

| 名称 | 类型 | 必须 | 示例 | 说明 |
|------|------|------|------|------|
| `start_price` | Number | ❌ | `10` | 到手价下限（元） |
| `end_price` | Number | ❌ | `100` | 到手价上限（元） |
| `start_tk_rate` | Number | ❌ | `1234` | 佣金率下限（1234=12.34%） |
| `end_tk_rate` | Number | ❌ | `5000` | 佣金率上限 |

#### 商品筛选

| 名称 | 类型 | 必须 | 示例 | 说明 |
|------|------|------|------|------|
| `cat` | String | ❌ | `16,18` | 后台类目ID，逗号分割，最多10个 |
| `is_tmall` | Boolean | ❌ | `true` | 是否天猫商品 |
| `is_overseas` | Boolean | ❌ | `false` | 是否海外商品 |
| `itemloc` | String | ❌ | `杭州` | 商品所在地 |
| `has_coupon` | Boolean | ❌ | `true` | 是否有优惠券 |
| `need_free_shipment` | Boolean | ❌ | `true` | 是否包邮 |
| `need_prepay` | Boolean | ❌ | `true` | 是否加入消费者保障 |
| `npx_level` | Number | ❌ | `2` | 牛皮癣程度：1不限 2无 3轻微 |
| `start_dsr` | Number | ❌ | `10` | 店铺DSR评分下限（0-50000） |

#### 质量筛选

| 名称 | 类型 | 必须 | 示例 | 说明 |
|------|------|------|------|------|
| `include_good_rate` | Boolean | ❌ | `true` | 好评率高于行业均值 |
| `include_pay_rate_30` | Boolean | ❌ | `true` | 转化率高于行业均值 |
| `include_rfd_rate` | Boolean | ❌ | `true` | 退款率低于行业均值 |

#### 排序

| 名称 | 类型 | 必须 | 示例 | 说明 |
|------|------|------|------|------|
| `sort` | String | ❌ | `tk_rate_des` | 排序字段+方向 |

排序可选值：

| 值 | 说明 |
|----|------|
| `tk_rate_des` | 佣金率降序 |
| `tk_rate_asc` | 佣金率升序 |
| `total_sales_des` | 销量降序 |
| `total_sales_asc` | 销量升序 |
| `tk_total_sales_des` | 累计推广量降序 |
| `tk_total_commi_des` | 总佣金支出降序 |
| `final_promotion_price_asc` | 到手价升序 |
| `final_promotion_price_des` | 到手价降序 |

#### 高级参数

| 名称 | 类型 | 必须 | 示例 | 说明 |
|------|------|------|------|------|
| `ip` | String | ❌ | `13.2.33.4` | 用户IP（影响邮费计算） |
| `relation_id` | String | ❌ | `3243` | 渠道关系ID |
| `special_id` | String | ❌ | `2323` | 会员运营ID |
| `promotion_type` | String | ❌ | `2` | 1=自购省 2=推广赚（代理模式） |
| `biz_scene_id` | String | ❌ | `1` | 1=动态ID转链 2=消费者比价 |
| `get_topn_rate` | Number | ❌ | `0` | 是否获取前N件佣金 0否 1是 |

### 4.2 响应参数

#### 顶层

| 名称 | 类型 | 说明 |
|------|------|------|
| `total_results` | Number | 符合条件的总结果数 |
| `result_list.map_data[]` | Array | 商品列表 |
| `uvid_msg` | String | uvid结果信息 |

#### 商品对象（map_data 内）

##### item_basic_info — 商品基础信息

| 名称 | 类型 | 说明 | VisionCart 映射 |
|------|------|------|-----------------|
| `item_id` | String | 淘宝客新商品ID | `id` (加前缀 `tb_`) |
| `title` | String | 商品标题 | `title` |
| `short_title` | String | 商品短标题 | — |
| `pict_url` | String | 商品主图 | `imageUrl` |
| `white_image` | String | 白底图 | — |
| `category_id` | Number | 叶子类目ID | — |
| `category_name` | String | 叶子类目名称 | — |
| `level_one_category_name` | String | 一级类目名称 | — |
| `seller_id` | Number | 卖家ID | — |
| `user_type` | Number | 卖家类型：0淘宝 1天猫 3特价版 | `selfOperated` (1=true) |
| `shop_title` | String | 店铺名称 | `shopName` |
| `volume` | Number | 30天销量 | `sales` |
| `sub_title` | String | 商品子标题 | — |
| `brand_name` | String | 品牌名称 | — |
| `provcity` | String | 所在地 | — |
| `annual_vol` | String | 年销量 | — |
| `real_post_fee` | String | 邮费 | — |
| `small_images` | String[] | 商品小图列表 | — |
| `tk_total_sales` | String | 淘客30天推广量 | — |

##### price_promotion_info — 价格促销信息

| 名称 | 类型 | 说明 | VisionCart 映射 |
|------|------|------|-----------------|
| `reserve_price` | String | 一口价（划线价） | `originalPrice` |
| `zk_final_price` | String | 售价 | `price` |
| `final_promotion_price` | String | 预估到手价 | — |
| `predict_rounding_up_price` | String | 预估凑单价 | — |
| `predict_rounding_up_price_desc` | String | 凑单价说明 | — |
| `final_promotion_target_type` | String | 到手价类型（10=直播间） | — |

**final_promotion_path_list[]** — 到手价优惠路径

| 名称 | 类型 | 说明 |
|------|------|------|
| `promotion_title` | String | 优惠名称（商品券/跨店满减/单品直降） |
| `promotion_desc` | String | 优惠文案（1件7.92折） |
| `promotion_fee` | String | 实际优惠金额（元） |
| `promotion_start_time` | String | 开始时间 |
| `promotion_end_time` | String | 结束时间 |
| `promotion_id` | String | 优惠ID |

**promotion_tag_list[]** — 标签

| 名称 | 类型 | 说明 |
|------|------|------|
| `tag_name` | String | 标签名（88VIP/花呗免息/猫超买返/是否包邮） |

**more_promotion_list[]** — 更多活动优惠

| 名称 | 类型 | 说明 |
|------|------|------|
| `promotion_title` | String | 优惠名称 |
| `promotion_desc` | String | 优惠文案 |
| `promotion_start_time` | String | 开始时间 |
| `promotion_end_time` | String | 结束时间 |
| `promotion_id` | String | 优惠ID |

**gov_subsidy** — 国家补贴

| 名称 | 类型 | 说明 |
|------|------|------|
| `tag_name` | String | "国家补贴" |
| `state_subsidy_info.max_rebate` | Number | 最高优惠比例(%) |
| `state_subsidy_info.min_rebate` | Number | 最低优惠比例(%) |
| `state_subsidy_info.max_discount` | String | 最高优惠金额(元) |
| `state_subsidy_info.min_discount` | String | 最低优惠金额(元) |
| `state_subsidy_info.province_list` | String[] | 生效省份 |

##### publish_info — 推广信息

| 名称 | 类型 | 说明 | VisionCart 映射 |
|------|------|------|-----------------|
| `click_url` | String | 宝贝推广链接 | `detailUrl` |
| `coupon_share_url` | String | 宝贝+券二合一链接 | — |
| `income_rate` | String | 收入比率(%)（佣金+补贴） | — |
| `commission_type` | String | 佣金类型：MKT/SP/COMMON/ZX | — |
| `rank_page_url` | String | 榜单URL | — |
| `include_dxjh` | String | 是否包含定向计划 | — |
| `two_hour_promotion_sales` | Number | 2小时推广销量 | — |
| `daily_promotion_sales` | Number | 当天推广销量 | — |

**income_info** — 佣金信息

| 名称 | 类型 | 说明 |
|------|------|------|
| `commission_rate` | String | 佣金比率 |
| `commission_amount` | String | 佣金金额 |
| `subsidy_rate` | String | 补贴比率 |
| `subsidy_amount` | String | 补贴金额 |
| `subsidy_upper_limit` | String | 补贴上限 |
| `subsidy_type` | String | 补贴类型 |

**sp_campaign_list[]** — 定向计划

| 名称 | 类型 | 说明 |
|------|------|------|
| `sp_cid` | String | 定向计划活动ID |
| `sp_name` | String | 计划名称 |
| `sp_rate` | String | 定向佣金率（1550=15.5%） |
| `sp_lock_status` | String | 是否锁佣 0否 1是 |
| `sp_apply_link` | String | 申请链接 |
| `sp_status` | String | 是否可用 1可用 0不可用 |

**topn_info** — 前N件佣金

| 名称 | 类型 | 说明 |
|------|------|------|
| `topn_quantity` | Number | 剩余库存 |
| `topn_total_count` | Number | 初始总库存 |
| `topn_start_time` | String | 开始时间 |
| `topn_end_time` | String | 结束时间 |
| `topn_rate` | String | 佣金率 |

##### tmall_rank_info — 天猫榜单

| 名称 | 类型 | 说明 |
|------|------|------|
| `tmall_rank_text` | String | 榜单描述（如"白茶热销榜·第5名"） |
| `tmall_rank_url` | String | 榜单URL |

##### presale_info — 预售信息

| 名称 | 类型 | 说明 |
|------|------|------|
| `presale_start_time` | Number | 付定金开始时间（毫秒） |
| `presale_end_time` | Number | 付定金结束时间（毫秒） |
| `presale_tail_start_time` | Number | 付尾款开始时间（毫秒） |
| `presale_tail_end_time` | Number | 付尾款结束时间（毫秒） |
| `presale_deposit` | String | 定金（元） |
| `presale_discount_fee_text` | String | 优惠信息 |

##### mgc_info — 线报内容

| 名称 | 类型 | 说明 |
|------|------|------|
| `price` | String | 价格 |
| `price_desc` | String | 价格描述 |
| `promotion_summary` | String | 文案 |
| `publish_time` | String | 发布时间（13位毫秒） |
| `valid_time` | String | 生效时间（0=实时） |

### 4.3 响应示例（JSON）

```json
{
  "tbk_dg_material_optional_upgrade_response": {
    "total_results": 1212,
    "result_list": {
      "map_data": [
        {
          "item_id": "qeqscd1231-uqwenqe",
          "item_basic_info": {
            "title": "复古女裤子秋冬九分裤萝卜裤显瘦高腰韩版2017新款",
            "short_title": "九分裤显瘦高腰韩版",
            "pict_url": "//img.alicdn.com/bao/uploaded/i4/xxx.jpg",
            "category_name": "牛仔裤",
            "user_type": 1,
            "shop_title": "魔黛娅内衣旗舰店",
            "volume": 30,
            "brand_name": "淘宝心选",
            "provcity": "浙江 杭州",
            "real_post_fee": "0.00"
          },
          "price_promotion_info": {
            "reserve_price": "102.00",
            "zk_final_price": "79.9",
            "final_promotion_price": "69.9",
            "final_promotion_path_list": {
              "final_promotion_path_map_data": [
                {
                  "promotion_title": "商品券",
                  "promotion_desc": "满7999减1300",
                  "promotion_fee": "1300.00"
                }
              ]
            },
            "promotion_tag_list": {
              "promotion_tag_map_data": [
                { "tag_name": "88VIP" }
              ]
            }
          },
          "publish_info": {
            "income_rate": "5.50",
            "click_url": "//item.taobao.com/item.htm?id=556633720749&...",
            "coupon_share_url": "//uland.taobao.com/coupon/edetail?...",
            "income_info": {
              "commission_rate": "55",
              "commission_amount": "12",
              "subsidy_rate": "11",
              "subsidy_amount": "4"
            },
            "two_hour_promotion_sales": 24,
            "daily_promotion_sales": 111
          }
        }
      ]
    }
  }
}
```

---

## 5. 物料精选升级版

> 根据物料ID和推广位获取推荐商品列表，支持相似商品推荐。

- **method**: `taobao.tbk.dg.material.recommend`
- **费用**: 免费
- **授权**: 不需要

### 5.1 请求参数

| 名称 | 类型 | 必须 | 示例 | 说明 |
|------|------|------|------|------|
| `material_id` | Number | ✅ | `123` | 官方物料ID（通过接口6获取） |
| `adzone_id` | Number | ✅ | `12345678` | 推广位ID |
| `page_no` | Number | ❌ | `1` | 页码，默认1 |
| `page_size` | Number | ❌ | `20` | 每页数量，默认20，范围1~100 |
| `item_id` | String | ❌ | `qeqscd1231` | 商品ID（相似推荐时必传，material_id=13256） |
| `favorites_id` | String | ❌ | `123445` | 选品库收藏夹ID |
| `relation_id` | Number | ❌ | `123456` | 渠道关系ID |
| `special_id` | String | ❌ | `2323` | 会员运营ID |
| `promotion_type` | String | ❌ | `2` | 1=自购省 2=推广赚 |

#### 设备推荐参数（需签署协议）

| 名称 | 类型 | 必须 | 示例 | 说明 |
|------|------|------|------|------|
| `device_type` | String | ❌ | `OAID` | 设备号类型：OAID / IDFA |
| `device_encrypt` | String | ❌ | `MD5` | 加密类型 |
| `device_value` | String | ❌ | `xxx` | MD5加密后的值（32位小写） |

### 5.2 响应参数

#### 顶层

| 名称 | 类型 | 说明 |
|------|------|------|
| `total_count` | Number | 商品总数 |
| `is_default` | String | 是否抄底推荐 |
| `result_list.map_data[]` | Array | 商品列表（结构同接口4） |
| `uvid_msg` | String | uvid结果信息 |

#### 商品对象

响应结构**与接口4完全一致**，包含 `item_basic_info`、`price_promotion_info`、`publish_info` 等嵌套对象。

额外字段：

| 名称 | 类型 | 说明 |
|------|------|------|
| `favorites_info.total_count` | Number | 选品库收藏夹总数 |
| `favorites_info.favorites_list[]` | Array | 收藏夹列表 |
| `scope_info.superior_brand` | String | 是否品牌精选 0否 1是 |

### 5.3 特殊物料ID

| material_id | 用途 | 必传参数 |
|-------------|------|----------|
| `13256` | 相似商品推荐 | `item_id`（必传） |
| 其他 | 按物料分类推荐 | `material_id` |

---

## 6. 物料ID列表查询

> 获取当前推广者可用的所有物料ID列表。

- **method**: `taobao.tbk.optimus.tou.material.ids.get`
- **费用**: 免费
- **授权**: 可选（不需要用户授权即可调用）

### 6.1 请求参数

| 名称 | 类型 | 必须 | 示例 | 说明 |
|------|------|------|------|------|
| `material_query.page_no` | Number | ❌ | `1` | 页码，默认1，范围1~100 |
| `material_query.page_size` | Number | ❌ | `20` | 每页数量，默认20，范围1~100 |
| `material_query.subject` | Number | ✅ | `1` | 物料主题类型 |
| `material_query.material_type` | Number | ✅ | `1` | 物料类型 |

**subject 物料主题类型：**

| 值 | 说明 |
|----|------|
| 1 | 促销活动 |
| 2 | 热门主题 |
| 3 | 精选榜单 |
| 4 | 行业频道 |
| 5 | 其他 |

**material_type 物料类型：**

| 值 | 说明 |
|----|------|
| 1 | 商品 |
| 2 | 权益 |

### 6.2 响应参数

| 名称 | 类型 | 说明 |
|------|------|------|
| `total_count` | Number | 物料ID总数 |
| `result_msg` | String | 结果描述 |
| `data.tou_materials[]` | Array | 物料列表 |

**tou_materials 对象：**

| 名称 | 类型 | 说明 |
|------|------|------|
| `material_id` | Number | 物料ID（传给接口4/5使用） |
| `material_name` | String | 物料名称 |
| `material_type` | Number | 物料类型 1商品 2权益 |
| `subject` | Number | 主题类型 |
| `start_time` | Number | 开始时间（Unix时间戳） |
| `end_time` | Number | 结束时间（Unix时间戳） |

### 6.3 响应示例

```json
{
  "tbk_optimus_tou_material_ids_get_response": {
    "total_count": 20,
    "result_msg": "test",
    "data": {
      "tou_materials": [
        {
          "material_name": "双11大促",
          "material_id": 123456,
          "material_type": 1,
          "subject": 1,
          "start_time": 1660875853,
          "end_time": 1660875853
        }
      ]
    }
  }
}
```

---

## 7. 店铺搜索

> 按关键词搜索淘宝客推广店铺，支持按佣金率、信用等级、商品数量等筛选。

- **method**: `taobao.tbk.shop.get`
- **费用**: 免费
- **授权**: 不需要

### 7.1 请求参数

| 名称 | 类型 | 必须 | 示例 | 说明 |
|------|------|------|------|------|
| `q` | String | ✅ | `女装` | 搜索关键词 |
| `fields` | String | ✅ | `user_id,shop_title,shop_type,seller_nick,pict_url,shop_url` | 需返回的字段列表 |
| `page_no` | Number | ❌ | `1` | 页码，默认1，范围1~100 |
| `page_size` | Number | ❌ | `20` | 每页数量，默认20，范围1~100 |
| `platform` | Number | ❌ | `1` | 链接形式：1=PC 2=无线，默认1 |
| `is_tmall` | Boolean | ❌ | `false` | 是否天猫店铺 |
| `sort` | String | ❌ | `commission_rate_des` | 排序（见下表） |
| `start_commission_rate` | Number | ❌ | `2000` | 佣金率下限（1~10000，2000=20%） |
| `end_commission_rate` | Number | ❌ | `123` | 佣金率上限 |
| `start_credit` | Number | ❌ | `1` | 信用等级下限（1~20） |
| `end_credit` | Number | ❌ | `20` | 信用等级上限 |
| `start_total_action` | Number | ❌ | `1` | 店铺商品总数下限 |
| `end_total_action` | Number | ❌ | `100` | 店铺商品总数上限 |
| `start_auction_count` | Number | ❌ | `123` | 累计推广商品下限 |
| `end_auction_count` | Number | ❌ | `200` | 累计推广商品上限 |

**排序可选值：**

| 值 | 说明 |
|----|------|
| `commission_rate_des` | 佣金率降序 |
| `commission_rate_asc` | 佣金率升序 |
| `auction_count_des` | 商品数量降序 |
| `auction_count_asc` | 商品数量升序 |
| `total_auction_des` | 销售总量降序 |
| `total_auction_asc` | 销售总量升序 |

### 7.2 响应参数

| 名称 | 类型 | 说明 |
|------|------|------|
| `total_results` | Number | 符合条件的总结果数 |
| `results.n_tbk_shop[]` | Array | 店铺列表 |

**n_tbk_shop 对象：**

| 名称 | 类型 | 说明 |
|------|------|------|
| `user_id` | Number | 卖家ID |
| `shop_title` | String | 店铺名称 |
| `shop_type` | String | 店铺类型：B=天猫，C=淘宝 |
| `seller_nick` | String | 卖家昵称 |
| `pict_url` | String | 店标图片URL |
| `shop_url` | String | 店铺地址URL |

### 7.3 响应示例（JSON）

```json
{
  "tbk_shop_get_response": {
    "total_results": 100,
    "results": {
      "n_tbk_shop": [
        {
          "user_id": 123,
          "shop_title": "女装店铺",
          "shop_type": "B",
          "seller_nick": "demo",
          "pict_url": "http://img01.taobaocdn.com/bao/uploaded/i1/xxx.jpg",
          "shop_url": "http://store.taobao.com/shop/xxx"
        }
      ]
    }
  }
}
```

### 7.4 错误码

| sub_code | 说明 | 解决方案 |
|----------|------|----------|
| `isv.invalid-parameter` | 非法参数 | 检查必传参数 |
| `isp.tbkapi-service-unavailable` | 内部服务不可用 | 稍后重试 |

---

## 8. 商品详情查询升级版

> 查询单个商品详情，包含佣金信息、优惠活动、店铺评分等完整数据。

- **method**: `taobao.tbk.item.info.upgrade.get`
- **费用**: 免费
- **授权**: 不需要

### 8.1 请求参数

| 名称 | 类型 | 必须 | 示例 | 说明 |
|------|------|------|------|------|
| `item_id` | String | ✅ | `3232` | 商品ID，多个用","分割，一次最多20个 |
| `ip` | String | ❌ | `11.22.33.43` | IP地址，影响邮费获取 |
| `biz_scene_id` | String | ❌ | `1` | 1=动态ID转链 2=消费者比价 3=商品库导购 |
| `promotion_type` | String | ❌ | `2` | 1=自购省 2=推广赚（代理模式） |
| `relation_id` | String | ❌ | `1` | 渠道关系ID |
| `manage_item_pub_id` | Number | ❌ | `1` | 商品库服务账户（场景id=3时） |
| `get_tlj_info` | Number | ❌ | `0` | 是否获取淘礼金剩余数量 0否 1是 |

### 8.2 响应参数

| 名称 | 类型 | 说明 |
|------|------|------|
| `results.tbk_item_detail[]` | Array | 商品详情列表 |

**tbk_item_detail 对象：**

| 名称 | 类型 | 说明 |
|------|------|------|
| `item_id` | String | 商品ID |
| `input_item_iid` | String | 输入的商品ID |
| `publish_info` | PublishInfo | 推广信息（佣金、销量等） |
| `price_promotion_info` | PromotionInfoMapData | 价格促销信息 |
| `item_basic_info` | BasicMapData | 商品基础信息 |
| `presale_info` | PresaleInfo | 预售信息 |

**publish_info 推广信息：**

| 名称 | 类型 | 说明 |
|------|------|------|
| `income_rate` | String | 收入比率(%) |
| `income_info.commission_rate` | String | 佣金比率 |
| `income_info.commission_amount` | String | 佣金金额 |
| `income_info.subsidy_rate` | String | 补贴比率 |
| `income_info.subsidy_amount` | String | 补贴金额 |
| `topn_info` | TopNInfoDTO | 前N件佣金信息 |
| `tlj_remain_num` | Number | 淘礼金剩余数量 |
| `two_hour_promotion_sales` | Number | 2小时推广销量 |
| `daily_promotion_sales` | Number | 当天推广销量 |
| `cpa_reward_type` | String | 额外奖励活动类型 |
| `cpa_reward_amount` | String | 额外奖励金额 |

**price_promotion_info 价格促销信息：**

| 名称 | 类型 | 说明 |
|------|------|------|
| `reserve_price` | String | 一口价 |
| `zk_final_price` | String | 折扣价 |
| `final_promotion_price` | String | 预估到手价 |
| `final_promotion_path_list` | Array | 优惠路径列表 |
| `promotion_tag_list` | Array | 标签列表（88VIP等） |
| `gov_subsidy` | GovSubsidyDTO | 国家补贴信息 |
| `activity_tag_list` | Array | 活动标签（淘宝好价节等） |

**item_basic_info 商品基础信息：**

| 名称 | 类型 | 说明 |
|------|------|------|
| `title` | String | 商品标题 |
| `short_title` | String | 短标题 |
| `pict_url` | String | 主图 |
| `white_image` | String | 白底图 |
| `small_images` | String[] | 小图列表 |
| `category_name` | String | 类目名称 |
| `level_one_category_name` | String | 一级类目 |
| `user_type` | Number | 0淘宝 1天猫 3特价版 |
| `shop_title` | String | 店铺名称 |
| `seller_id` | Number | 卖家ID |
| `volume` | Number | 30天销量 |
| `annual_vol` | String | 年销量 |
| `provcity` | String | 所在地 |
| `brand_name` | String | 品牌名称 |
| `free_shipment` | Boolean | 是否包邮 |
| `is_prepay` | Boolean | 是否加入消费者保障 |
| `superior_brand` | String | 是否品牌精选 0否 1是 |
| `shop_dsr` | Number | 店铺DSR评分 |
| `ratesum` | Number | 卖家等级 |
| `i_rfd_rate` | Boolean | 退款率是否低于行业均值 |
| `h_good_rate` | Boolean | 好评率是否高于行业均值 |
| `h_pay_rate30` | Boolean | 成交转化是否高于行业均值 |
| `item_url` | String | 商品链接 |
| `tmall_desc_url` | String | PC详情页 |
| `taobao_desc_url` | String | H5详情页 |
| `material_lib_type` | String | 商品库类型 |

### 8.3 响应示例

```json
{
  "tbk_item_info_upgrade_get_response": {
    "results": {
      "tbk_item_detail": [
        {
          "item_id": "4112264076123",
          "input_item_iid": "qeqscd1231-uqwenqe",
          "publish_info": {
            "income_rate": "15.5",
            "income_info": {
              "commission_rate": "55",
              "commission_amount": "12",
              "subsidy_rate": "11",
              "subsidy_amount": "4"
            },
            "two_hour_promotion_sales": 24,
            "daily_promotion_sales": 111
          },
          "price_promotion_info": {
            "reserve_price": "102.00",
            "zk_final_price": "88.00",
            "final_promotion_price": "69.9"
          },
          "item_basic_info": {
            "title": "连衣裙",
            "pict_url": "http://gi4.md.alicdn.com/bao/uploaded/i4/xxx.jpg",
            "shop_title": "xx旗舰店",
            "volume": 1,
            "user_type": 1
          }
        }
      ]
    }
  }
}
```

---

## 9. 推广券详情查询

> 传入商品ID+券ID，或传入me参数，查询阿里妈妈推广券详细信息。

- **method**: `taobao.tbk.coupon.get`
- **费用**: 免费
- **授权**: 不需要

### 9.1 请求参数

| 名称 | 类型 | 必须 | 示例 | 说明 |
|------|------|------|------|------|
| `me` | String | ❌ | `nfr%2BYTo2k1...` | 带券ID与商品ID的加密串 |
| `item_id` | String | ❌ | `123` | 商品ID |
| `activity_id` | String | ❌ | `sdfwe3eefsdf` | 券ID |

**注意**：`me` 和 `item_id+activity_id` 二选一传入。

### 9.2 响应参数

| 名称 | 类型 | 说明 |
|------|------|------|
| `data` | MapData | 优惠券数据 |

**data 对象：**

| 名称 | 类型 | 说明 |
|------|------|------|
| `coupon_start_fee` | String | 优惠券门槛金额 |
| `coupon_amount` | String | 优惠券金额 |
| `coupon_remain_count` | Number | 优惠券剩余量 |
| `coupon_total_count` | Number | 优惠券总量 |
| `coupon_start_time` | String | 开始时间 |
| `coupon_end_time` | String | 结束时间 |
| `coupon_src_scene` | Number | 券类型：1全网公开券 4妈妈渠道券 |
| `coupon_type` | Number | 券属性：0店铺券 1单品券 |
| `coupon_activity_id` | String | 券ID |

### 9.3 响应示例

```json
{
  "tbk_coupon_get_response": {
    "data": {
      "coupon_start_fee": "29.00",
      "coupon_amount": "10.00",
      "coupon_remain_count": 26996,
      "coupon_total_count": 30000,
      "coupon_start_time": "2017-08-15",
      "coupon_end_time": "2017-08-17",
      "coupon_src_scene": 1,
      "coupon_type": 0,
      "coupon_activity_id": "xsdss"
    }
  }
}
```

### 9.4 错误码

| 错误码 | 说明 | 解决方案 |
|--------|------|----------|
| `1001` | 非法的me参数 | 传入正确的me参数值 |
| `10000` | 宝贝已下架或非淘客宝贝 | 更换itemId |
| `10` | 优惠券不存在 | 重新查询 |
| `11` | 优惠券已领完 | 重新查询 |
| `12` | 优惠券已过期 | 重新查询 |
| `13` | 优惠券不适用该商品 | 重新查询 |

---

## 10. 商品详情查询(简版)

> 批量查询商品基础信息，字段较少但查询效率高。

- **method**: `taobao.tbk.item.info.get`
- **费用**: 免费
- **授权**: 不需要

### 10.1 请求参数

| 名称 | 类型 | 必须 | 示例 | 说明 |
|------|------|------|------|------|
| `num_iids` | String | ✅ | `123,456` | 商品ID串，用,分割，最大40个 |
| `platform` | Number | ❌ | `1` | 1=PC 2=无线，默认1 |
| `ip` | String | ❌ | `11.22.33.43` | IP地址 |
| `biz_scene_id` | String | ❌ | `1` | 1=动态ID转链 2=消费者比价 3=商品库导购 |
| `promotion_type` | String | ❌ | `2` | 1=自购省 2=推广赚 |
| `relation_id` | String | ❌ | `1` | 渠道关系ID |
| `manage_item_pub_id` | Number | ❌ | `1` | 商品库服务账户 |

### 10.2 响应参数

| 名称 | 类型 | 说明 |
|------|------|------|
| `results.n_tbk_item[]` | Array | 商品列表 |

**n_tbk_item 对象：**

| 名称 | 类型 | 说明 |
|------|------|------|
| `num_iid` | String | 商品ID |
| `input_num_iid` | String | 输入的商品ID |
| `title` | String | 商品标题 |
| `pict_url` | String | 主图 |
| `small_images` | String[] | 小图列表 |
| `reserve_price` | String | 一口价 |
| `zk_final_price` | String | 折扣价 |
| `user_type` | Number | 0淘宝 1天猫 3特价版 |
| `provcity` | String | 所在地 |
| `item_url` | String | 商品链接 |
| `seller_id` | Number | 卖家ID |
| `nick` | String | 店铺名称 |
| `volume` | Number | 30天销量 |
| `cat_name` | String | 一级类目 |
| `cat_leaf_name` | String | 叶子类目 |
| `is_prepay` | Boolean | 是否加入消费者保障 |
| `free_shipment` | Boolean | 是否包邮 |
| `shop_dsr` | Number | 店铺DSR评分 |
| `ratesum` | Number | 卖家等级 |
| `superior_brand` | String | 是否品牌精选 |
| `hot_flag` | String | 是否热门商品 |
| `material_lib_type` | String | 商品库类型 |
| `presale_info` | PresaleInfo | 预售信息 |
| `ju_online_start_time` | String | 聚划算开始时间 |
| `ju_online_end_time` | String | 聚划算结束时间 |
| `sale_price` | String | 活动价 |
| `kuadian_promotion_info` | String | 跨店满减信息 |

### 10.3 响应示例

```json
{
  "tbk_item_info_get_response": {
    "results": {
      "n_tbk_item": [
        {
          "num_iid": "123",
          "input_num_iid": "sdfqere-123dfqweq",
          "title": "连衣裙",
          "pict_url": "http://gi4.md.alicdn.com/bao/uploaded/i4/xxx.jpg",
          "reserve_price": "102.00",
          "zk_final_price": "88.00",
          "user_type": 1,
          "volume": 1,
          "nick": "xx旗舰店",
          "cat_name": "女装",
          "provcity": "杭州"
        }
      ]
    }
  }
}
```

---

## 11. 七接口关系与使用场景

### 调用流程

```
┌─────────────────────────────────────────────────────────────────┐
│                    VisionCart 使用场景                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  场景A: 关键词搜索比价（现有功能）                                 │
│  ┌─────────────────────────────────────────┐                   │
│  │  接口4: material.optional.upgrade        │                   │
│  │  入参: q=关键词 + adzone_id + 筛选条件    │                   │
│  │  出参: 商品列表 + 推广链接 + 佣金          │                   │
│  └─────────────────────────────────────────┘                   │
│                                                                 │
│  场景B: 热门榜单/促销活动推荐（新增）                              │
│  ┌──────────────────┐    ┌──────────────────────────┐           │
│  │ 接口6: 获取物料ID  │ →  │ 接口5: material.recommend │           │
│  │ (一次性/缓存)      │    │ 入参: material_id + adzone │           │
│  └──────────────────┘    └──────────────────────────┘           │
│                                                                 │
│  场景C: 相似商品推荐（新增）                                      │
│  ┌─────────────────────────────────────────┐                   │
│  │  接口5: material.recommend                │                   │
│  │  入参: material_id=13256 + item_id + adzone│                   │
│  └─────────────────────────────────────────┘                   │
│                                                                 │
│  场景D: 店铺搜索（新增）                                          │
│  ┌─────────────────────────────────────────┐                   │
│  │  接口7: shop.get                          │                   │
│  │  入参: q=关键词 + fields + 筛选条件        │                   │
│  │  出参: 店铺列表 + 店铺链接                  │                   │
│  └─────────────────────────────────────────┘                   │
│                                                                 │
│  【详情查询场景】                                                  │
│                                                                 │
│  场景E: 商品详情页（新增）                                         │
│  ┌─────────────────────────────────────────┐                   │
│  │  接口8: item.info.upgrade.get             │                   │
│  │  入参: item_id                            │                   │
│  │  出参: 完整商品详情 + 佣金 + 优惠 + 店铺评分 │                   │
│  └─────────────────────────────────────────┘                   │
│                                                                 │
│  场景F: 批量刷新商品信息（新增）                                    │
│  ┌─────────────────────────────────────────┐                   │
│  │  接口10: item.info.get                    │                   │
│  │  入参: num_iids=逗号分隔ID列表             │                   │
│  │  出参: 批量商品基础信息                    │                   │
│  └─────────────────────────────────────────┘                   │
│                                                                 │
│  场景G: 优惠券详情（新增）                                         │
│  ┌─────────────────────────────────────────┐                   │
│  │  接口9: coupon.get                        │                   │
│  │  入参: item_id + activity_id              │                   │
│  │  出参: 券门槛/金额/剩余量/有效期            │                   │
│  └─────────────────────────────────────────┘                   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### VisionCart 集成建议

| 场景 | 接口 | 建议端点 | 缓存策略 |
|------|------|----------|----------|
| 搜索比价 | 接口4 | `POST /api/v1/search/products`（已有） | 5分钟 Redis |
| 热门推荐 | 接口5 | `GET /api/v1/search/recommend`（新增） | 10分钟 Redis |
| 相似推荐 | 接口5 | `GET /api/v1/search/similar/{itemId}`（新增） | 30分钟 Redis |
| 物料列表 | 接口6 | `GET /api/v1/search/materials`（新增） | 1小时 Redis |
| 店铺搜索 | 接口7 | `GET /api/v1/search/shops`（新增） | 10分钟 Redis |
| 商品详情 | 接口8 | `GET /api/v1/products/{itemId}`（新增） | 10分钟 Redis |
| 批量查询 | 接口10 | `POST /api/v1/products/batch`（新增） | 5分钟 Redis |
| 优惠券详情 | 接口9 | `GET /api/v1/coupons/{activityId}`（新增） | 5分钟 Redis |

---

## 12. VisionCart 字段映射

### 淘宝客响应 → ProductCard 映射

```java
// TaobaoSearchService.java 现有映射逻辑
ProductCard card = new ProductCard(
    "tb_" + numIid,                    // id: 加平台前缀
    title,                              // title: 商品标题
    pictUrl,                            // imageUrl: 商品主图
    new BigDecimal(zkFinalPrice),       // price: 售价
    new BigDecimal(reservePrice),       // originalPrice: 划线价
    "淘宝",                             // platform: 固定值
    userType == 1,                      // selfOperated: 1=天猫
    shopTitle,                          // shopName: 店铺名
    0.0,                                // rating: 淘宝不提供评分
    volume != null ? volume : 0,        // sales: 30天销量
    0.0,                                // similarity: 搜索场景无相似度
    tags,                               // tags: 天猫/包邮/有券等
    clickUrl                            // detailUrl: 推广链接
);
```

### 可扩展字段（当前未使用）

| 淘宝客字段 | 用途 | 建议 |
|------------|------|------|
| `income_rate` | 佣金率 | 可在商品卡片展示"赚X%" |
| `commission_amount` | 佣金金额 | 可展示"佣金¥X" |
| `coupon_share_url` | 领券链接 | 可增加"领券"按钮 |
| `final_promotion_price` | 到手价 | 可展示"到手价¥X" |
| `brand_name` | 品牌名 | 可用于品牌筛选 |
| `provcity` | 所在地 | 可展示发货地 |
| `tmall_rank_info` | 榜单信息 | 可展示"热销榜第X名" |
| `promotion_tag_list` | 优惠标签 | 可展示88VIP/免息等标签 |
| `gov_subsidy` | 国补信息 | 可展示"国补最高减¥X" |
| `predict_rounding_up_price` | 凑单价 | 可展示"凑单价¥X" |

---

## 附录

### A. 错误处理

```json
{
  "error_response": {
    "code": "50",
    "msg": "Remote service error",
    "sub_code": "isv.invalid-parameter",
    "sub_msg": "非法参数"
  }
}
```

常见错误码：

| sub_code | 说明 | 解决方案 |
|----------|------|----------|
| `isv.invalid-parameter` | 参数非法 | 检查必传参数和格式 |
| `isv.appkey-not-exist` | AppKey不存在 | 检查app_key配置 |
| `ip.error` | IP限制 | 检查服务器白名单 |
| `isp.top-remote-connection-timeout` | 请求超时 | 重试或检查网络 |

### B. 配置项

```yaml
# application.yml
visioncart:
  taobao:
    api-url: https://eco.taobao.com/router/rest
    app-key: ${TAOBAO_APP_KEY}
    app-secret: ${TAOBAO_APP_SECRET}
    adzone-id: ${TAOBAO_ADZONE_ID}
```

### C. 参考链接

- 淘宝开放平台: https://open.taobao.com
- 淘宝联盟推广位管理: https://pub.alimama.com（推广管理 → 推广位管理）
- 物料ID汇总: https://market.m.taobao.com/app/qn/toutiao-new/index-pc.html#/detail/10628875
- 淘宝客新商品ID升级白皮书: https://www.yuque.com/taobaolianmengguanfangxiaoer/zmig94/tfyt0pahmlpzu2ud
