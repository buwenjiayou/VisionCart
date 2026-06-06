#!/usr/bin/env bash
# check-i18n.sh — CI check for hardcoded English, mojibake, and i18n violations
# Usage: ./scripts/check-i18n.sh
# Exit code 0 = pass, 1 = violations found

set -euo pipefail

RED='\033[0;31m'
YELLOW='\033[1;33m'
GREEN='\033[0;32m'
NC='\033[0m'

ERRORS=0
WARNINGS=0

echo "=== VisionCart i18n / Encoding Check ==="
echo ""

# ==============================
# 1. Mojibake detection (Android Kotlin)
# ==============================
echo "--- Checking Android Kotlin for mojibake ---"

# Common mojibake character sequences (UTF-8 misinterpreted as GBK/GB2312)
MOJIBAKE_PATTERNS=(
  '淇'    # 修 -> 淇
  '鎺'    # 排 -> 鎺
  '瑙'    # 解 -> 瑙
  '绛'    # 筛 -> 绛
  '澶'    # 失 -> 澶
  '娓'    # 清 -> 娓
  '鍒'    # 删 -> 鍒
  '鐣'    # 保 -> 鐣
  '€'     # GBK artifact
)

ANDROID_KT=$(find android/app/src/main/java -name "*.kt" -type f 2>/dev/null || true)
for file in $ANDROID_KT; do
  for pattern in "${MOJIBAKE_PATTERNS[@]}"; do
    if grep -q "$pattern" "$file" 2>/dev/null; then
      echo -e "${RED}MOJIBAKE${NC} in $file: found '$pattern'"
      grep -n "$pattern" "$file" | head -3
      ERRORS=$((ERRORS + 1))
    fi
  done
done

# ==============================
# 2. Hardcoded English user-facing strings (Android Kotlin)
# ==============================
echo ""
echo "--- Checking Android Kotlin for hardcoded English strings ---"

# English strings that should be in strings.xml
# Exclude: imports, package, comments, Log., TAG, const val, buildconfig, http, url, json keys
ENGLISH_PATTERNS=(
  'showToast("[A-Z][a-z]'
  'toastMessage = "[A-Z][a-z]'
  'nlpMessage = "[A-Z][a-z]'
  'filterStatusMessage = "[A-Z][a-z]'
  'progressStep = "[A-Z][a-z]'
  'Toast\.makeText.*"[A-Z][a-z]'
)

for file in $ANDROID_KT; do
  for pattern in "${ENGLISH_PATTERNS[@]}"; do
    matches=$(grep -n "$pattern" "$file" 2>/dev/null | grep -v '// ' | grep -v 'import ' | grep -v 'Log\.' || true)
    if [ -n "$matches" ]; then
      echo -e "${YELLOW}ENGLISH${NC} in $file:"
      echo "$matches" | head -3
      WARNINGS=$((WARNINGS + 1))
    fi
  done
done

# ==============================
# 3. Backend English user-facing messages
# ==============================
echo ""
echo "--- Checking Java backend for English user messages ---"

JAVA_FILES=$(find backend/src/main/java -name "*.java" -type f 2>/dev/null || true)

# Look for ApiResponse.fail() or message = "English text"
for file in $JAVA_FILES; do
  # Check for English-only messages in ApiResponse.fail()
  matches=$(grep -n 'ApiResponse\.fail.*"[A-Z][a-z].*"' "$file" 2>/dev/null | grep -v '//' || true)
  if [ -n "$matches" ]; then
    echo -e "${YELLOW}ENGLISH MSG${NC} in $file:"
    echo "$matches" | head -3
    WARNINGS=$((WARNINGS + 1))
  fi

  # Check for English message in ActionResult / return statements
  matches=$(grep -n '"[A-Z][a-z]\{3,\}.*"' "$file" 2>/dev/null \
    | grep -v '//' \
    | grep -v 'import ' \
    | grep -v 'class ' \
    | grep -v 'interface ' \
    | grep -v '@' \
    | grep -v 'log\.' \
    | grep -v 'LOG\.' \
    | grep -v 'logger\.' \
    | grep -v 'Pattern\.' \
    | grep -v 'case "' \
    | grep -v 'switch' \
    | grep -v 'return "' \
    | grep -E '(message|Message|toast|Toast|"Filters|"Action|"Unauthorized|"Session)' || true)
  if [ -n "$matches" ]; then
    echo -e "${YELLOW}ENGLISH${NC} in $file:"
    echo "$matches" | head -3
    WARNINGS=$((WARNINGS + 1))
  fi
done

# ==============================
# 4. Check strings.xml completeness
# ==============================
echo ""
echo "--- Checking strings.xml completeness ---"

STRINGS_XML="android/app/src/main/res/values/strings.xml"
if [ -f "$STRINGS_XML" ]; then
  STRING_COUNT=$(grep -c '<string name=' "$STRINGS_XML" 2>/dev/null || echo "0")
  echo "strings.xml has $STRING_COUNT string entries"
  if [ "$STRING_COUNT" -lt 30 ]; then
    echo -e "${YELLOW}WARNING${NC}: strings.xml has fewer than 30 entries — may be missing translations"
    WARNINGS=$((WARNINGS + 1))
  fi
else
  echo -e "${RED}ERROR${NC}: strings.xml not found!"
  ERRORS=$((ERRORS + 1))
fi

# ==============================
# Summary
# ==============================
echo ""
echo "=== Results ==="
echo -e "Errors:   ${RED}$ERRORS${NC}"
echo -e "Warnings: ${YELLOW}$WARNINGS${NC}"

if [ "$ERRORS" -gt 0 ]; then
  echo -e "${RED}FAILED${NC} — $ERRORS critical issues found"
  exit 1
else
  echo -e "${GREEN}PASSED${NC} — no critical issues"
  exit 0
fi
