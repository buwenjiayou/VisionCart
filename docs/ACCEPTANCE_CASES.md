# 最小功能验证用例

| 编号 | 用例 | 操作 | 预期结果 |
|------|------|------|----------|
| VC-01 | 健康检查 | 访问 `/api/v1/health` | 返回 `status=up` |
| VC-02 | 邮箱验证码 | 调用 `/api/v1/auth/send-code` | 真实 SMTP 发送验证码 |
| VC-03 | 登录 | 调用 `/api/v1/auth/login` | 返回 JWT 和用户信息 |
| VC-04 | 拍照识物 | 上传商品图片到 `/recognition/analyze` | 返回真实视觉模型识别出的类目和属性 |
| VC-05 | 商品检索 | 带 JWT 调用 `/search/products` | 返回已配置平台的真实商品列表和平台价格统计 |
| VC-06 | 属性修正 | 将颜色或款式改为新值 | 返回更新后的属性和刷新后的商品 |
| VC-07 | NLP 筛选 | 输入“1000 元以内黑色款，要评价 4.8 分以上，按销量排” | 返回结构化 filter，并可刷新商品 |
| VC-08 | 建议卡片 | 执行 `sort_by_price_asc` | 商品按价格升序 |
| VC-09 | 收藏 | POST `/favorites` | MySQL 中新增收藏记录 |
| VC-10 | 历史 | GET `/history` | 返回最近识别记录 |
| VC-11 | Android 真机 | 安装 APK 并连接局域网后端 | 可登录、上传图片、搜索商品 |
| VC-12 | Web 控制台 | 打开 `web/index.html` 并填入 JWT | 可检查后端、上传图片和调用真实接口 |
