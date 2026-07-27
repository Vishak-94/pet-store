#!/usr/bin/env bash
# Stop all running services by their listening ports.
cd "$(dirname "$0")"
PORTS=(8080 8081 8082 8083 8085 8086 8087 8088)
for p in "${PORTS[@]}"; do
  pid=$(lsof -ti :"$p" 2>/dev/null || true)
  if [ -n "$pid" ]; then
    echo "stopping service on :$p (pid $pid)"
    kill "$pid" 2>/dev/null || true
  fi
done

# Stop the container fleet started by run-all.sh: the JMS broker plus (best-effort) the
# mongo + mongo-express containers. 'docker compose down' stops every compose service.
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  echo "stopping broker + mongo containers"
  docker compose down >/dev/null 2>&1 || true
fi
echo "done."
