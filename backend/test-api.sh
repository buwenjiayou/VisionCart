#!/bin/bash

# VisionCart API 测试脚本

BASE_URL="http://localhost:8080"

echo "=== 1. 健康检查 ==="
curl -s "$BASE_URL/api/v1/health"
echo ""

echo ""
echo "=== 2. 发送验证码 ==="
curl -s -X POST "$BASE_URL/api/v1/auth/send-code" \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com"}'
echo ""

echo ""
echo "=== 3. 登录（需要验证码）==="
echo "请先发送验证码，然后输入验证码："
read -p "验证码: " CODE
curl -s -X POST "$BASE_URL/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"test@example.com\",\"code\":\"$CODE\"}"
echo ""

echo ""
echo "=== 4. NLP 解析（需要 token）==="
read -p "输入 token: " TOKEN
curl -s -X POST "$BASE_URL/api/v1/nlp/parse" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"text":"我想买一双红色的耐克运动鞋"}'
echo ""

echo ""
echo "=== 5. 商品搜索 ==="
curl -s -X POST "$BASE_URL/api/v1/search/products" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"keywords":["运动鞋"],"platforms":["taobao"]}'
echo ""
