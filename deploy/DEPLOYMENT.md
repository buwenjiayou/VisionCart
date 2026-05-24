# 部署指南

## 后端服务

1. 新建后端运行环境，例如云服务器、Railway、Render 或其他 Java 17 运行平台。
2. 准备 MySQL 8.x 和 Redis 7.x。
3. 将仓库连接到部署平台。
4. 设置构建命令：

```bash
./gradlew :backend:bootJar
```

5. 设置启动命令：

```bash
java -jar backend/build/libs/backend-0.1.0.jar --spring.profiles.active=prod
```

6. 配置环境变量：

```text
PORT=8080
PUBLIC_BASE_URL=https://your-api-domain.example.com
MYSQL_URL=jdbc:mysql://...
MYSQL_USERNAME=...
MYSQL_PASSWORD=...
MYSQL_DRIVER=com.mysql.cj.jdbc.Driver
REDIS_HOST=...
REDIS_PORT=6379
REDIS_PASSWORD=...
JWT_SECRET=...
JWT_EXPIRATION=604800000
MAIL_HOST=smtp.qq.com
MAIL_PORT=587
MAIL_USERNAME=...
MAIL_PASSWORD=...
ARK_BASE_URL=https://ark.cn-beijing.volces.com/api/v3
ARK_API_KEY=...
ARK_LLM_MODEL=your-llm-endpoint-id
ARK_VISION_API_KEY=...
ARK_VISION_MODEL=your-vision-endpoint-id
PDD_API_URL=https://gw-api.pinduoduo.com/api/router
PDD_CLIENT_ID=...
PDD_CLIENT_SECRET=...
PDD_PID=...
TAOBAO_API_URL=https://eco.taobao.com/router/rest
TAOBAO_APP_KEY=...
TAOBAO_APP_SECRET=...
TAOBAO_ADZONE_ID=...
EBAY_APP_ID=...
EBAY_CERT_ID=...
EBAY_DEV_ID=...
EBAY_MARKETPLACE_ID=EBAY_US
```

## Web 控制台

1. 将 `web/` 目录发布到静态站点服务。
2. 打开页面后，将 API Base URL 设置为后端公网地址。
3. 登录后复制 JWT 到页面中，再进行真实识别和搜索验证。

## Android 安装包

1. 用 Android Studio 打开项目。
2. 在 `local.properties` 中设置 `VISIONCART_API_BASE_URL` 为后端公网地址。
3. 选择 `Build > Generate Signed Bundle / APK`。
4. 使用安全的签名证书生成发布包，证书不要提交到仓库。
