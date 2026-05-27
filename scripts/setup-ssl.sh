#!/bin/bash
# SSL证书申请脚本 - Let's Encrypt with Certbot

# 颜色输出
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${YELLOW}========================================${NC}"
echo -e "${YELLOW}  VisionCart SSL证书申请脚本${NC}"
echo -e "${YELLOW}========================================${NC}"
echo ""

# 检查是否以root运行
if [ "$EUID" -ne 0 ]; then
    echo -e "${RED}请使用 sudo 运行此脚本${NC}"
    exit 1
fi

# 检查域名
if [ -z "$1" ]; then
    echo -e "${RED}用法: sudo ./setup-ssl.sh <你的域名>${NC}"
    echo -e "${YELLOW}例如: sudo ./setup-ssl.sh visioncart.example.com${NC}"
    exit 1
fi

DOMAIN=$1

echo -e "${YELLOW}正在为域名: $DOMAIN 申请SSL证书...${NC}"
echo ""

# 安装 certbot
echo -e "${YELLOW}[1/3] 安装 Certbot...${NC}"
if command -v certbot &> /dev/null; then
    echo -e "${GREEN}Certbot 已安装${NC}"
else
    apt update
    apt install -y certbot python3-certbot-nginx
    echo -e "${GREEN}Certbot 安装完成${NC}"
fi
echo ""

# 检查nginx配置
echo -e "${YELLOW}[2/3] 检查 Nginx 配置...${NC}"
if [ ! -f "/etc/nginx/sites-available/visioncart" ]; then
    echo -e "${YELLOW}创建 Nginx 配置文件...${NC}"
    cat > /etc/nginx/sites-available/visioncart << 'EOF'
server {
    listen 80;
    server_name DOMAIN_PLACEHOLDER;

    location /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }

    location / {
        proxy_pass http://localhost:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_cache_bypass $http_upgrade;
    }
}
EOF
    sed -i "s/DOMAIN_PLACEHOLDER/$DOMAIN/g" /etc/nginx/sites-available/visioncart
    ln -sf /etc/nginx/sites-available/visioncart /etc/nginx/sites-enabled/
    rm -f /etc/nginx/sites-enabled/default
    nginx -t && systemctl reload nginx
    echo -e "${GREEN}Nginx 配置完成${NC}"
else
    echo -e "${GREEN}Nginx 配置已存在${NC}"
fi
echo ""

# 创建certbot webroot
mkdir -p /var/www/certbot

# 申请证书
echo -e "${YELLOW}[3/3] 申请 SSL 证书...${NC}"
echo -e "${YELLOW}========================================${NC}"
echo -e "${YELLOW}请按提示操作：${NC}"
echo -e "  1. 输入你的邮箱（用于证书到期提醒）"
echo -e "  2. 同意服务条款 → 输入 ${GREEN}Y${NC}"
echo -e "  3. 是否分享邮箱给EFF → 输入 ${GREEN}N${NC}（可选）"
echo -e "  4. 是否重定向 HTTP → HTTPS → 输入 ${GREEN}2${NC}"
echo -e "${YELLOW}========================================${NC}"
echo ""

certbot --nginx -d $DOMAIN

# 检查结果
if [ $? -eq 0 ]; then
    echo ""
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}  ✅ SSL证书申请成功！${NC}"
    echo -e "${GREEN}========================================${NC}"
    echo ""
    echo -e "证书位置: ${YELLOW}/etc/letsencrypt/live/$DOMAIN/${NC}"
    echo -e "证书自动续期: ${GREEN}已启用${NC} (systemd timer)"
    echo ""
    echo -e "测试续期命令: ${YELLOW}certbot renew --dry-run${NC}"
    echo ""
    echo -e "访问地址: ${GREEN}https://$DOMAIN${NC}"
else
    echo ""
    echo -e "${RED}========================================${NC}"
    echo -e "${RED}  ❌ SSL证书申请失败${NC}"
    echo -e "${RED}========================================${NC}"
    echo ""
    echo -e "请检查:"
    echo -e "  1. 域名是否正确解析到本服务器IP"
    echo -e "  2. 80端口是否开放"
    echo -e "  3. Nginx是否正常运行"
    exit 1
fi
