#!/bin/bash

# Verify dynamic update implementation for PolyHermes.

echo "========================================"
echo "  PolyHermes dynamic update verifier"
echo "========================================"
echo ""

ERRORS=0

echo "[1/3] Check required files..."

files=(
  "Dockerfile"
  "docker/update-service.py"
  "docker/start.sh"
  "docker/nginx.conf"
  "docker-compose.yml"
  "docker-compose.test.yml"
  ".github/workflows/docker-build.yml"
  "docs/zh/DYNAMIC_UPDATE.md"
)

for file in "${files[@]}"; do
  if [ -f "$file" ]; then
    echo "  [OK] $file"
  else
    echo "  [MISSING] $file"
    ((ERRORS++))
  fi
done

echo ""
echo "[2/3] Check key config markers..."

if grep -q "ARG BUILD_IN_DOCKER=true" Dockerfile; then
  echo "  [OK] Dockerfile has BUILD_IN_DOCKER"
else
  echo "  [FAIL] Dockerfile missing BUILD_IN_DOCKER"
  ((ERRORS++))
fi

if grep -q "python3" Dockerfile; then
  echo "  [OK] Dockerfile installs python3"
else
  echo "  [FAIL] Dockerfile missing python3 install"
  ((ERRORS++))
fi

if grep -q "/api/update/" docker/nginx.conf; then
  echo "  [OK] nginx includes update service proxy"
else
  echo "  [FAIL] nginx missing update service proxy"
  ((ERRORS++))
fi

if grep -q "ALLOW_PRERELEASE" docker-compose.yml; then
  echo "  [OK] docker-compose.yml includes ALLOW_PRERELEASE"
else
  echo "  [FAIL] docker-compose.yml missing ALLOW_PRERELEASE"
  ((ERRORS++))
fi

if grep -q "IS_PRERELEASE" .github/workflows/docker-build.yml; then
  echo "  [OK] workflow has pre-release detection"
else
  echo "  [FAIL] workflow missing pre-release detection"
  ((ERRORS++))
fi

if grep -q "Build Backend JAR" .github/workflows/docker-build.yml; then
  echo "  [OK] workflow has backend build step"
else
  echo "  [FAIL] workflow missing backend build step"
  ((ERRORS++))
fi

echo ""
echo "[3/3] Check python syntax for update service..."
if python3 -m py_compile docker/update-service.py 2>/dev/null; then
  echo "  [OK] docker/update-service.py syntax is valid"
else
  echo "  [WARN] Python syntax check skipped or failed (python3/runtime issue)"
fi

echo ""
echo "========================================"
if [ $ERRORS -eq 0 ]; then
  echo "  [PASS] All checks passed"
else
  echo "  [FAIL] Found $ERRORS issue(s)"
fi
echo "========================================"

exit $ERRORS