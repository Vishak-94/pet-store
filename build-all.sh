#!/usr/bin/env bash
# Build every module in dependency order. Libraries are installed to the local
# Maven repo (~/.m2) first because the services depend on them.
set -euo pipefail
cd "$(dirname "$0")"

# Resolve a Java 21 JDK (see run-all.sh for rationale).
resolve_java21() {
  if [ -n "${JAVA_HOME:-}" ] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"21'; then
    echo "$JAVA_HOME"; return
  fi
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    /usr/libexec/java_home -v 21 2>/dev/null && return
  fi
  echo "/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home"
}
JAVA_HOME="$(resolve_java21)"
export JAVA_HOME
echo "Using JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1

# 1) Shared libraries + multi-module parents → install to ~/.m2 (order matters:
#    catalog-service-client is needed by cart-lib; auth-client by the services).
LIBS=(petstore-messaging auth-service catalog-service customer-service cart-lib)
for m in "${LIBS[@]}"; do
  echo "==> install $m"
  (cd "$m" && mvn -q clean install -DskipTests)
done

# 2) Leaf services (executable apps) → package.
APPS=(admin-office-service inventory-service notification-service petstore-app-v1)
for m in "${APPS[@]}"; do
  echo "==> package $m"
  (cd "$m" && mvn -q clean package -DskipTests)
done

echo "BUILD COMPLETE — all modules built."
