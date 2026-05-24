# 端到端验收指南

## 1. 启动真实后端

1. 确认 MySQL、Redis 已启动。
2. 确认 `.env` 已填入邮箱、模型、拼多多、淘宝、eBay 等真实配置。
3. 构建并启动：

```powershell
.\gradlew.bat :backend:bootJar --no-daemon
.\start_backend.ps1 -Port 8080
```

4. 访问 `/api/v1/health`，确认返回 `status=up`。

## 2. 登录验证

1. 调用 `/api/v1/auth/send-code` 发送邮箱验证码。
2. 用收到的验证码调用 `/api/v1/auth/login`。
3. 保存返回的 JWT，后续接口都带 `Authorization: Bearer <token>`。

## 3. 图片识别链路

1. 在 Android 应用或 Web 控制台上传真实商品图片。
2. 确认 `/recognition/analyze` 返回类目、品牌、颜色、款式和关键词。
3. 确认识别记录写入 MySQL 历史表。

## 4. 商品搜索链路

1. 使用识别属性调用 `/search/products`。
2. 确认已配置的平台返回真实商品。
3. 检查商品标题、价格、图片、平台、店铺、销量和跳转链接。
4. 检查 `platform_stats` 的最低价、均价和数量是否合理。

## 5. 自然语言筛选链路

输入：

```text
1000 元以内黑色款，要评价 4.8 分以上，按销量排
```

预期：

- `/nlp/parse` 返回结构化 `filter`。
- 搜索接口使用该 `filter` 后商品列表刷新。
- Redis 中可以命中相同语义查询缓存。

## 6. Android 真机链路

1. 在 `local.properties` 设置 `VISIONCART_API_BASE_URL` 为电脑局域网 IP 或线上 API。
2. 安装 APK。
3. 完成登录、拍照/相册识别、商品搜索、收藏、历史查看。
4. 开启悬浮窗权限后验证悬浮窗入口和截图识物流程。

## 7. 通过标准

- 所有核心接口使用真实后端和真实第三方服务。
- 所有密钥只存在于 `.env`、云端环境变量或本地私密文档。
- 公开仓库不包含个人账号、平台密钥、运行日志、数据库文件或 APK 构建产物。
