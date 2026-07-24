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
echo "done."
