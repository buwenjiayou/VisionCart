# VisionCart 阿里云 Docker 部署指南

以下步骤以 Ubuntu/Debian 系统为准，推荐使用 Docker Compose 部署后端、MySQL 和 Redis。真实密码、JWT 密钥、平台 API Key 只放服务器 `.env`，不要提交到 Git。

## 1. 安装 Docker

```bash
curl -fsSL https://get.docker.com | sh
systemctl enable docker
systemctl start docker
docker version
```

## 2. 拉取代码

```bash
git clone https://github.com/buwenjiayou/VisionCart.git
cd VisionCart
```

## 3. 配置环境变量

```bash
cp .env.example .env
chmod 600 .env
vim .env
```

生产建议至少修改：

```text
PUBLIC_BASE_URL=https://your-domain.example.com
JWT_SECRET=<至少32位随机字符串>
MYSQL_PASSWORD=<强密码>
MYSQL_ROOT_PASSWORD=<强密码>
ALIYUN_BAILIAN_API_KEY=<真实百炼Key>
PDD_CLIENT_ID=<真实值>
PDD_CLIENT_SECRET=<真实值>
PDD_PID=<真实值>
TAOBAO_APP_KEY=<真实值>
TAOBAO_APP_SECRET=<真实值>
TAOBAO_ADZONE_ID=<真实值>
VISIONCART_ALLOWED_ORIGINS=https://your-domain.example.com
VISIONCART_ADMIN_USER_IDS=<可访问 metrics/prometheus 的用户 ID，多个用逗号分隔>
VISIONCART_WS_QUERY_TOKEN_ENABLED=false
```

Docker Compose 默认会让后端连接 compose 内的 `mysql` 服务；只有使用外部数据库时才需要设置 `DOCKER_MYSQL_URL`。

生产部署使用 Flyway 管理表结构，JPA 固定为 `validate`。首次部署请先确认 `backend/src/main/resources/db/migration` 中的迁移脚本已覆盖当前 schema，不再使用 `JPA_DDL_AUTO=update` 自动建表。

## 4. 启动后端、MySQL、Redis

```bash
cd deploy
docker compose --env-file ../.env up -d --build
docker compose --env-file ../.env ps
docker compose --env-file ../.env logs -f backend
```

验证：

```bash
curl http://127.0.0.1:8080/api/v1/health
curl https://your-domain.example.com/api/v1/health
curl -H "Authorization: Bearer <admin-jwt>" http://127.0.0.1:8080/actuator/prometheus
```

`/api/v1/health` 用于负载均衡健康检查；`/api/v1/health/deep`、`/api/v1/metrics/**`、`/actuator/metrics/**`、`/actuator/prometheus` 需要登录，其中 metrics/prometheus 还要求当前用户 ID 在 `VISIONCART_ADMIN_USER_IDS` 内。

## 5. 防火墙和安全组

阿里云安全组放行：

- TCP 8080：临时直接访问后端。
- TCP 80/443：配置 Nginx/HTTPS 后使用。

服务器本机如果启用 `ufw`：

```bash
ufw allow 8080/tcp
ufw allow 80/tcp
ufw allow 443/tcp
```

MySQL 和 Redis 在 compose 中只绑定 `127.0.0.1`，不对公网开放。

## 6. Android 真机包

本地 `local.properties`：

```properties
VISIONCART_API_BASE_URL=https://your-domain.example.com/
```

Debug 构建允许本地 HTTP 地址。Release 构建必须配置 HTTPS，且不能是 localhost、`10.0.2.2` 或局域网 IP，否则 Gradle 会在 `preReleaseBuild` 阶段失败。

重新构建并安装：

```powershell
.\gradlew.bat :android:app:assembleDebug --no-daemon
adb install -r android\app\build\outputs\apk\debug\app-debug.apk
```

## 7. 可选：Nginx + HTTPS

有域名后，先将域名 A 记录解析到服务器公网 IP，然后执行：

```bash
apt update
apt install -y nginx
cp deploy/nginx-visioncart.conf /etc/nginx/sites-available/visioncart
sed -i 's/visioncart.example.com/你的域名/g' /etc/nginx/sites-available/visioncart
ln -sf /etc/nginx/sites-available/visioncart /etc/nginx/sites-enabled/visioncart
nginx -t && systemctl reload nginx
apt install -y certbot python3-certbot-nginx
certbot --nginx -d 你的域名
```

HTTPS 启用后，把 `.env` 中：

```text
PUBLIC_BASE_URL=https://你的域名
VISIONCART_ALLOWED_ORIGINS=https://你的域名
```

Android 改为：

```properties
VISIONCART_API_BASE_URL=https://你的域名/
```

开启 HTTPS 后，Release APK 只信任 HTTPS；本地 cleartext network security config 仅存在于 debug source set。

## 8. 常用运维命令

```bash
cd VisionCart/deploy
docker compose --env-file ../.env logs -f backend
docker compose --env-file ../.env restart backend
docker compose --env-file ../.env pull
docker compose --env-file ../.env up -d --build
docker compose --env-file ../.env down
```
