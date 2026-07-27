#!/usr/bin/env bash
# Start all services. The ActiveMQ Artemis broker now runs as a STANDALONE container
# (docker-compose.yml) on :61616 and is started FIRST; every service — including the
# storefront — connects to it as a plain client. (Previously petstore-app-v1 hosted
# an embedded broker; it no longer does.)
set -uo pipefail
cd "$(dirname "$0")"

# 0) Externalized JMS broker (container) — must be up before any service connects.
start_broker() {
  if ! command -v docker >/dev/null 2>&1; then
    echo "!! docker not found — install it (or run 'colima start') then re-run." >&2
    exit 1
  fi
  if ! docker info >/dev/null 2>&1; then
    echo "!! docker daemon not reachable — start it (e.g. 'colima start') then re-run." >&2
    exit 1
  fi
  echo "==> starting broker (Artemis container, :61616)"
  docker compose up -d broker >/dev/null 2>&1 || { echo "    !! docker compose up failed"; exit 1; }
  # Wait for the CORE acceptor to accept a TCP connection on :61616.
  for i in $(seq 1 60); do
    if nc -z localhost 61616 >/dev/null 2>&1; then echo "    broker up"; return 0; fi
    sleep 1
  done
  echo "    !! broker did not open :61616 in 60s — see 'docker compose logs broker'"; exit 1
}
start_broker

# 0b) MongoDB + mongo-express (OPC MongoDB track). BEST-EFFORT: no service depends on
# Mongo yet, so a failure here WARNS but does not abort the fleet (unlike the broker).
# mongo runs as a single-node replica set; its compose healthcheck self-initiates rs0.
start_mongo() {
  echo "==> starting mongo + mongo-express (containers)"
  if ! docker compose up -d mongo mongo-express >/dev/null 2>&1; then
    echo "    !! mongo containers failed to start — skipping (fleet continues); see 'docker compose logs mongo'" >&2
    return 0
  fi
  # Wait for mongo to accept a TCP connection on :27018 (host mapping).
  for i in $(seq 1 30); do
    if nc -z localhost 27018 >/dev/null 2>&1; then
      echo "    mongo up (:27018) · mongo-express UI: http://localhost:8971 (admin/pass)"; return 0
    fi
    sleep 1
  done
  echo "    !! mongo did not open :27018 in 30s — skipping (fleet continues)" >&2
}
start_mongo

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

# Run the fleet under the 'dev' Spring profile. Its ONLY effect is to enable each
# service's H2 web console (/h2-console) for local DB browsing — off by default because
# the console is an unauthenticated SQL shell. No other dev-profile beans exist, so this
# is a safe local-only convenience. To run WITHOUT the DB consoles: SPRING_PROFILES_ACTIVE= ./run-all.sh
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE-dev}"
[ -n "$SPRING_PROFILES_ACTIVE" ] && echo "Spring profile: $SPRING_PROFILES_ACTIVE (H2 consoles enabled)"

# OPC persistence store: 'h2' (default, file-based) or 'mongo' (single-node rs0 :27018).
# When 'mongo', ONLY order-processing-service gets the extra 'mongo' profile — every other
# service stays on H2. Choosing mongo without a reachable mongo container will make OPC fail
# to start (surfaced in logs/order-processing-service.log), so start_mongo above must have succeeded.
OPC_STORE="${OPC_STORE-h2}"
[ "$OPC_STORE" = "mongo" ] && echo "OPC store: mongo (order-processing-service runs on MongoDB :27018)"

start() {  # name  jar  port  started-marker  [extra-profiles]
  local name=$1 jar=$2 port=$3 marker=$4 extra=${5-}
  # Layer any per-service profile (e.g. 'mongo') on top of the fleet-wide SPRING_PROFILES_ACTIVE,
  # so only THIS service sees it. Empty 'extra' → the service inherits the global profile unchanged.
  local profiles="$SPRING_PROFILES_ACTIVE"
  if [ -n "$extra" ]; then
    profiles="${profiles:+$profiles,}$extra"
    echo "==> starting $name (:$port) [profiles: $profiles]"
  else
    echo "==> starting $name (:$port)"
  fi
  SPRING_PROFILES_ACTIVE="$profiles" nohup "$JAVA" -jar "$jar" > "logs/$name.log" 2>&1 &
  for i in $(seq 1 60); do
    grep -q "$marker" "logs/$name.log" 2>/dev/null && { echo "    $name up"; return 0; }
    sleep 1
  done
  echo "    !! $name did not report started in 60s — see logs/$name.log"; return 1
}

# 1) Storefront (broker client; the broker container is already up above).
start petstore-app-v1 petstore-app-v1/target/petstore-app-v1-1.0.0.jar 8080 "Started PetStoreApplication"

# 2) auth-service (issuer) — others verify against it.
start auth-service       auth-service/app/target/auth-service-1.0.0.jar       8086 "Started AuthServiceApplication"

# 3) domain + back-office services (connect to the broker + auth).
start customer-service   customer-service/app/target/customer-service-1.0.0.jar   8081 "Started CustomerServiceApplication"
start catalog-service    catalog-service/app/target/catalog-service-1.0.0.jar     8083 "Started CatalogServiceApplication"
start order-processing-service order-processing-service/app/target/order-processing-service-1.0.0.jar 8088 "Started OrderProcessingApplication" "$([ "$OPC_STORE" = "mongo" ] && echo mongo)"
start admin-office-service admin-office-service/target/admin-office-service-1.0.0.jar 8082 "Started WarehouseServiceApplication"
start inventory-service  inventory-service/app/target/inventory-service-1.0.0.jar     8085 "Started InventoryServiceApplication"
start notification-service notification-service/target/notification-service-1.0.0.jar 8087 "Started NotificationServiceApplication"

echo ""
echo "======================================================================"
echo " All services started. Open these in a browser:"
echo "----------------------------------------------------------------------"
echo " UIs (have a web front-end):"
echo "   Storefront (shop + customer account)  http://localhost:8080/"
echo "     login:    http://localhost:8080/login        (j2ee / j2ee)"
echo "     register: http://localhost:8080/register"
echo "     cart:     http://localhost:8080/cart"
echo "   Warehouse / admin console             http://localhost:8082/warehouse/orders"
echo "     login:    http://localhost:8082/warehouse/login   (admin / admin)"
echo "   Inventory console                     http://localhost:8085/inventory"
echo "     login:    http://localhost:8085/inventory/login   (supplier / supplier)"
echo "   JMS broker console (Artemis)          http://localhost:8161/            (admin / admin)"
echo "----------------------------------------------------------------------"
echo " API-only services (JSON — no UI; browsing returns data or 401):"
echo "   customer-service   http://localhost:8081     catalog-service  http://localhost:8083"
echo "   auth-service       http://localhost:8086     notification     http://localhost:8087"
echo "   order-processing   http://localhost:8088"
echo "   health probe example: http://localhost:8088/actuator/health"
echo "----------------------------------------------------------------------"
echo " Database consoles (H2 web UI — only when started with the 'dev' profile):"
echo "   customer DB          http://localhost:8081/h2-console   (jdbc:h2:file:./data/customer)"
echo "   catalog DB           http://localhost:8083/h2-console   (jdbc:h2:file:./data/catalog)"
echo "   inventory DB         http://localhost:8085/h2-console   (jdbc:h2:file:./data/inventory)"
echo "   auth DB              http://localhost:8086/h2-console   (jdbc:h2:file:./data/auth)"
if [ "$OPC_STORE" = "mongo" ]; then
  echo "   order-processing DB  MongoDB (:27018) — browse via mongo-express http://localhost:8971 (admin/pass)"
else
  echo "   order-processing DB  http://localhost:8088/h2-console   (jdbc:h2:file:./data/opc)"
fi
echo "     H2 login: user 'sa', password blank; paste the JDBC URL shown above (add ;AUTO_SERVER=TRUE)."
echo "     All DBs now persist to ./data/ across restarts. Delete ./data/ for a clean slate."
echo "----------------------------------------------------------------------"
echo " Note: customer-service has NO separate UI — customer screens live in the"
echo "       storefront (:8080). The storefront (:8080) has NO database (publish-only)."
echo "======================================================================"
echo "Logs in ./logs/ — stop everything with ./stop-all.sh"
