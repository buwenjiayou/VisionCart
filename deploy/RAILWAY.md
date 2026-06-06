# Railway Deployment

Railway uses `deploy/railway.json` to build `:backend:bootJar`, start the backend with the `prod` profile, and check `/api/v1/health`.

Required production variables:

```text
SPRING_PROFILES_ACTIVE=prod
PUBLIC_BASE_URL=https://<your-domain>
JWT_SECRET=<at-least-32-random-chars>
MYSQL_URL=jdbc:mysql://<host>:<port>/<db>?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
MYSQL_USERNAME=<user>
MYSQL_PASSWORD=<password>
REDIS_HOST=<redis-host>
REDIS_PORT=<redis-port>
REDIS_PASSWORD=<redis-password-if-any>
FLYWAY_ENABLED=true
VISIONCART_ADMIN_USER_IDS=<admin-user-id[,admin-user-id]>
VISIONCART_WS_QUERY_TOKEN_ENABLED=false
VISIONCART_ALLOWED_ORIGINS=https://<your-domain>
```

Use Flyway migrations with JPA `validate`; do not set `JPA_DDL_AUTO=update` in production.

Monitoring endpoints:

```text
/api/v1/health          public lightweight health check
/api/v1/health/deep     authenticated deep health check
/actuator/prometheus    authenticated and admin-only
/actuator/metrics/**    authenticated and admin-only
```

Android release builds must use `VISIONCART_API_BASE_URL=https://<your-domain>/` in `local.properties`.
