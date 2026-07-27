#!/usr/bin/env bash
# Tail every service log at once, each line prefixed with its service name and colour-coded,
# so you can watch the whole fleet in ONE terminal during a demo. run-all.sh writes one file
# per service to ./logs/<service>.log — this multiplexes them.
#
# Usage:
#   ./logs-all.sh                 # follow all service logs live (Ctrl-C to stop)
#   ./logs-all.sh -e              # follow, but show only WARN/ERROR/Exception lines
#   ./logs-all.sh auth inventory  # follow only the named services (substring match)
#
# Tip: for a security-403 hunt, run with -e and reproduce the click — the offending
# service's stack trace / DEBUG line lands here immediately, tagged with its name.
set -uo pipefail
cd "$(dirname "$0")"

LOG_DIR="logs"
ERRORS_ONLY=0
FILTERS=()

for arg in "$@"; do
  case "$arg" in
    -e|--errors) ERRORS_ONLY=1 ;;
    -h|--help)
      grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) FILTERS+=("$arg") ;;
  esac
done

if [ ! -d "$LOG_DIR" ]; then
  echo "No $LOG_DIR/ directory yet — start the fleet first with ./run-all.sh" >&2
  exit 1
fi

# Only tail the current fleet's per-service logs (skip ad-hoc test/backup logs like *-p.log).
SERVICES=(petstore-app-v1 auth-service customer-service catalog-service \
          order-processing-service admin-office-service inventory-service notification-service)

# Stable colour per service so lines are easy to scan.
declare -a COLORS=(31 32 33 34 35 36 91 92)   # ANSI fg codes

files=()
labels=()
colors=()
idx=0
for svc in "${SERVICES[@]}"; do
  f="$LOG_DIR/$svc.log"
  [ -f "$f" ] || continue
  # If name filters were given, keep only matching services.
  if [ "${#FILTERS[@]}" -gt 0 ]; then
    keep=0
    for pat in "${FILTERS[@]}"; do [[ "$svc" == *"$pat"* ]] && keep=1; done
    [ "$keep" -eq 1 ] || { idx=$((idx+1)); continue; }
  fi
  files+=("$f")
  labels+=("$svc")
  colors+=("${COLORS[$((idx % ${#COLORS[@]}))]}")
  idx=$((idx+1))
done

if [ "${#files[@]}" -eq 0 ]; then
  echo "No matching log files under $LOG_DIR/ (looked for: ${SERVICES[*]})" >&2
  exit 1
fi

echo "Following ${#files[@]} log(s): ${labels[*]}"
[ "$ERRORS_ONLY" -eq 1 ] && echo "(errors-only: WARN / ERROR / Exception lines)"
echo "Press Ctrl-C to stop."
echo "----------------------------------------------------------------------"

# tail -F all files together; a per-service background reader tags each line. Using separate
# tails (not one `tail -F a b c`) lets us prefix every line with the right service + colour
# without parsing tail's own "==> file <==" banners.
pids=()
cleanup() { for p in "${pids[@]}"; do kill "$p" 2>/dev/null; done; }
trap cleanup EXIT INT TERM

for i in "${!files[@]}"; do
  f="${files[$i]}"; name="${labels[$i]}"; c="${colors[$i]}"
  ( tail -n 5 -F "$f" 2>/dev/null | while IFS= read -r line; do
      if [ "$ERRORS_ONLY" -eq 1 ]; then
        case "$line" in
          *ERROR*|*WARN*|*Exception*|*"    at "*) ;;   # keep errors + stack frames
          *) continue ;;
        esac
      fi
      printf '\033[%sm%-24s\033[0m %s\n' "$c" "[$name]" "$line"
    done ) &
  pids+=("$!")
done

wait
