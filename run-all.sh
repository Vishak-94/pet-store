#!/usr/bin/env bash
# Start all services. petstore-app-v1 hosts the embedded ActiveMQ Artemis broker
# on :61616, so it MUST start first; the other services connect to that broker.
set -uo pipefail
cd "$(dirname "$0")"

# Resolve a Java 21 runtime (the apps are compiled for Java 21 / class 61).
# Prefer JAVA_HOME if it already points at a 21 JDK, else auto-detect via
# /usr/libexec/java_home, else fall back to the Corretto 21 default path.
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
JAVA="$JAVA_HOME/bin/java"
echo "Using JAVA_HOME=$JAVA_HOME"
"$JAVA" -version 2>&1 | head -1
mkdir -p logs

start() {  # name  jar  port  started-marker
  local name=$1 jar=$2 port=$3 marker=$4
  echo "==> starting $name (:$port)"
  nohup "$JAVA" -jar "$jar" > "logs/$name.log" 2>&1 &
  for i in $(seq 1 60); do
    grep -q "$marker" "logs/$name.log" 2>/dev/null && { echo "    $name up"; return 0; }
    sleep 1
  done
  echo "    !! $name did not report started in 60s — see logs/$name.log"; return 1
}

# 1) Broker host + storefront FIRST (owns the :61616 broker).
start petstore-app-v1 petstore-app-v1/target/petstore-app-v1-1.0.0.jar 8080 "Started PetStoreApplication"

# 2) auth-service (issuer) — others verify against it.
start auth-service       auth-service/app/target/auth-service-1.0.0.jar       8086 "Started AuthServiceApplication"

# 3) domain + back-office services (connect to the broker + auth).
start customer-service   customer-service/app/target/customer-service-1.0.0.jar   8081 "Started CustomerServiceApplication"
start catalog-service    catalog-service/app/target/catalog-service-1.0.0.jar     8083 "Started CatalogServiceApplication"
start order-processing-service order-processing-service/app/target/order-processing-service-1.0.0.jar 8088 "Started OrderProcessingApplication"
start admin-office-service admin-office-service/target/admin-office-service-1.0.0.jar 8082 "Started WarehouseServiceApplication"
start inventory-service  inventory-service/target/inventory-service-1.0.0.jar     8085 "Started InventoryServiceApplication"
start notification-service notification-service/target/notification-service-1.0.0.jar 8087 "Started NotificationServiceApplication"

echo ""
echo "All services started. Storefront: http://localhost:8080/"
echo "Logs in ./logs/ — stop everything with ./stop-all.sh"
