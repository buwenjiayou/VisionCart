# 商品平台 API 文档

## eBay API

### 凭证

| 环境 | App ID | Cert ID |
|------|--------|---------|
| 沙盒 | `${EBAY_SANDBOX_APP_ID}` | `${EBAY_SANDBOX_CERT_ID}` |
| 生产 | `${EBAY_PROD_APP_ID}` | `${EBAY_PROD_CERT_ID}` |

Dev ID: `${EBAY_DEV_ID}`

### 核心接口

| 功能 | 端点 | 方法 | 授权 |
|------|------|------|------|
| 商品搜索 | `/buy/browse/v1/item_summary/search` | GET | App Token |
| 商品详情 | `/buy/browse/v1/item/{item_id}` | GET | App Token |
| 分类树 | `/commerce/taxonomy/v1/category_tree/{tree_id}` | GET | App Token |
| 同产品多卖家 | `/commerce/catalog/v1_beta/product` | GET | App Token |

### 搜索参数

| 参数 | 说明 | 示例 |
|------|------|------|
| q | 关键词 | `iPhone 16` |
| sort | 排序 | `price` (升序), `-price` (降序) |
| filter | 筛选 | `price:[500..1000]` |
| category_ids | 分类ID | `9355` |

---

## 淘宝联盟 API

### 凭证

| 项目 | 值 |
|------|-----|
| App Key | `${TAOBAO_APP_KEY}` |
| App Secret | `${TAOBAO_APP_SECRET}` |
| Site ID | `${TAOBAO_SITE_ID}` |
| Adzone ID | `${TAOBAO_ADZONE_ID}` |

### 核心接口

| 功能 | 接口 | 权限包 |
|------|------|--------|
| 商品搜索 | `taobao.tbk.dg.material.optional.upgrade` | 27939 |
| 店铺搜索 | `taobao.tbk.shop.get` | 16516 |
| 商品详情 | `taobao.tbk.item.info.get` | 16189 |

### 搜索参数

| 参数 | 说明 | 示例 |
|------|------|------|
| q | 关键词 | `手机` |
| sort | 排序 | `price_asc`, `price_des`, `total_sales_des` |
| start_price / end_price | 价格区间 | `100`, `500` |
| has_coupon | 有优惠券 | `true` |

---

## 拼多多 / 多多进宝 API

### 凭证

| 项目 | 值 |
|------|-----|
| client_id | `${PDD_CLIENT_ID}` |
| client_secret | `${PDD_CLIENT_SECRET}` |
| PID | `${PDD_PID}` |
| 备用 PID | `${PDD_BACKUP_PID}` |
| API 网关 | `https://gw-api.pinduoduo.com/api/router` |
| 数据格式 | `JSON` |

> 注意：`client_secret` 是机密字段，只应放在后端服务或受控环境变量中，不应暴露到前端、公开仓库或客户端安装包。

### 权限包

| 权限包 | API 数量 | 已测状态 |
|------|---------|---------|
| 获取商品信息 | 3 | 已测通 |

### 核心接口

| 功能 | 接口 | 方法 | 授权 / 备案要求 |
|------|------|------|----------------|
| 商品搜索 | `pdd.ddk.goods.search` | POST | 需要已授权备案的 `pid` 或 `custom_parameters` |
| 商品推荐 | `pdd.ddk.goods.recommend.get` | POST | 应用签名 |
| 商品详情 | `pdd.ddk.goods.detail` | POST | 应用签名，使用 `goods_sign` |
| 授权备案查询 | `pdd.ddk.member.authority.query` | POST | 查询 `pid` 是否已完成备案 |
| 推广链接生成 | `pdd.ddk.goods.promotion.url.generate` | POST | 可生成带授权流程的推广链接 |

### 公共参数

| 参数 | 说明 | 示例 |
|------|------|------|
| type | API 接口名 | `pdd.ddk.goods.search` |
| client_id | 应用 ID | `${PDD_CLIENT_ID}` |
| timestamp | 秒级 Unix 时间戳 | `1710000000` |
| data_type | 返回数据格式 | `JSON` |
| sign | 请求签名 | 按签名规则生成的大写 MD5 |

### 签名规则

1. 将所有请求参数按参数名升序排序。
2. 排除 `sign` 字段。
3. 按 `key + value` 形式拼接所有参数。
4. 在拼接字符串前后分别加上 `client_secret`。
5. 对最终字符串做 MD5，并转为大写。

```text
sign = MD5(client_secret + key1 + value1 + key2 + value2 + ... + client_secret).toUpperCase()
```

### 授权备案查询

接口：`pdd.ddk.member.authority.query`

业务参数：

```json
{
  "pid": "${PDD_PID}"
}
```

本次测试结果：

```json
{
  "authority_query_response": {
    "bind": 1
  }
}
```

说明：`bind: 1` 表示 PID 已完成授权备案；未备案时商品搜索会返回 `60001`。

### 商品搜索

接口：`pdd.ddk.goods.search`

| 参数 | 说明 | 示例 |
|------|------|------|
| keyword | 搜索关键词 | `手机` |
| page | 页码 | `1` |
| page_size | 每页数量，范围 `10-100` | `10` |
| pid | 已授权备案的推广位 | `${PDD_PID}` |

完整请求体示例：

```json
{
  "type": "pdd.ddk.goods.search",
  "client_id": "${PDD_CLIENT_ID}",
  "timestamp": 1710000000,
  "data_type": "JSON",
  "keyword": "手机",
  "page": 1,
  "page_size": 10,
  "pid": "${PDD_PID}",
  "sign": "按签名规则生成"
}
```

本次测试结果：

```json
{
  "ok": true,
  "total_count": 1000,
  "count": 10
}
```

返回示例：

```json
{
  "goods_id": 939144832144,
  "goods_sign": "E9X2eMMqGgRuX-lxwfDAOxErijUw0v3KLw_J6u8ChXc5",
  "goods_name": "点石制笔芯跳按动中性笔双珠顺滑速干子弹头口袋笔刷题考试笔0208",
  "min_group_price": 450,
  "mall_name": "",
  "coupon_discount": 0,
  "promotion_rate": 60
}
```

常见错误：

| 错误 | 原因 | 处理方式 |
|------|------|---------|
| `pageSize的取值范围是10-100` | `page_size` 小于 10 或大于 100 | 将 `page_size` 设置为 `10-100` |
| `60001` / 未传入已经授权备案过的相关参数 | PID 未完成备案或未被识别 | 先完成授权备案，再确认 `bind: 1` |

### 商品推荐

接口：`pdd.ddk.goods.recommend.get`

| 参数 | 说明 | 示例 |
|------|------|------|
| channel_type | 频道类型 | `0` |
| offset | 偏移量 | `0` |
| limit | 返回数量 | `10` |

完整请求体示例：

```json
{
  "type": "pdd.ddk.goods.recommend.get",
  "client_id": "${PDD_CLIENT_ID}",
  "timestamp": 1710000000,
  "data_type": "JSON",
  "channel_type": 0,
  "offset": 0,
  "limit": 10,
  "sign": "按签名规则生成"
}
```

本次测试结果：

```json
{
  "ok": true,
  "count": 10
}
```

返回示例：

```json
{
  "goods_id": 956812432347,
  "goods_sign": "E9v2fKw57U9uX-lxwfbAO_IZHd0Xjs44_JBCIxFbmm",
  "goods_name": "儿童止痒舒缓棒防蚊虫叮咬宝宝清凉修护止痒膏舒缓消包消肿户外",
  "min_group_price": 590,
  "mall_name": ""
}
```

### 商品详情

接口：`pdd.ddk.goods.detail`

| 参数 | 说明 | 示例 |
|------|------|------|
| goods_sign | 商品签名，从搜索或推荐结果中获取 | `E9v2fKw57U9uX-lxwfbAO_IZHd0Xjs44_JBCIxFbmm` |

完整请求体示例：

```json
{
  "type": "pdd.ddk.goods.detail",
  "client_id": "${PDD_CLIENT_ID}",
  "timestamp": 1710000000,
  "data_type": "JSON",
  "goods_sign": "E9v2fKw57U9uX-lxwfbAO_IZHd0Xjs44_JBCIxFbmm",
  "sign": "按签名规则生成"
}
```

本次测试结果：

```json
{
  "ok": true,
  "count": 1
}
```

返回示例：

```json
{
  "goods_id": 956812432347,
  "goods_sign": "E9v2fKw57U9uX-lxwfbAO_IZHd0Xjs44_JBCIxFbmm",
  "goods_name": "儿童止痒舒缓棒防蚊虫叮咬宝宝清凉修护止痒膏舒缓消包消肿户外",
  "min_group_price": 590,
  "mall_name": ""
}
```

注意：商品详情接口当前应使用 `goods_sign`；`goods_id_list` 已下线。

### Python 调用示例

```python
import hashlib
import json
import os
import time
import urllib.request

CLIENT_ID = os.environ["PDD_CLIENT_ID"]
CLIENT_SECRET = os.environ["PDD_CLIENT_SECRET"]
PID = os.environ["PDD_PID"]
API_URL = "https://gw-api.pinduoduo.com/api/router"


def make_sign(params: dict) -> str:
    raw = CLIENT_SECRET
    for key in sorted(params):
        if key != "sign" and params[key] is not None:
            raw += key + str(params[key])
    raw += CLIENT_SECRET
    return hashlib.md5(raw.encode("utf-8")).hexdigest().upper()


def pdd_call(api_type: str, biz_params: dict) -> dict:
    params = {
        "type": api_type,
        "client_id": CLIENT_ID,
        "timestamp": int(time.time()),
        "data_type": "JSON",
    }
    params.update(biz_params)
    params["sign"] = make_sign(params)

    body = json.dumps(params, separators=(",", ":")).encode("utf-8")
    request = urllib.request.Request(
        API_URL,
        data=body,
        headers={"Content-Type": "application/json;charset=UTF-8"},
        method="POST",
    )

    with urllib.request.urlopen(request, timeout=25) as response:
        return json.loads(response.read().decode("utf-8"))


search_result = pdd_call(
    "pdd.ddk.goods.search",
    {
        "keyword": "手机",
        "page": 1,
        "page_size": 10,
        "pid": PID,
    },
)
print(json.dumps(search_result, ensure_ascii=False, indent=2))
```
---

## 相关链接

- [eBay 开发者平台](https://developer.ebay.com)
- [淘宝开放平台](https://open.taobao.com)
- [淘宝联盟后台](https://pub.alimama.com)
- [拼多多开放平台](https://open.pinduoduo.com)
- [多多进宝后台](https://jinbao.pinduoduo.com)
- [拼多多 API 网关](https://gw-api.pinduoduo.com/api/router)
