#!/usr/bin/env bash
# Start / stop / inspect the Dwell services (identity, product-catalog, api-gateway).
#
#   scripts/dwell.sh start   [all|service...] [--build]   build if needed, start, wait until listening
#   scripts/dwell.sh stop    [all|service...] [--force]   graceful stop (SIGTERM, SIGKILL after 30s)
#   scripts/dwell.sh restart [all|service...] [--build] [--force]
#   scripts/dwell.sh status                               pid / port / up-down for each service
#   scripts/dwell.sh logs <service> [-f]                  pretty-print the service's JSON log
#
#   --build  rebuild the jars first. With restart, the code is compiled before anything is stopped,
#            so a broken build never leaves you without running services.
#   --force  also stop this project's services that were started outside the script
#            (IntelliJ, mvn spring-boot:run, a manual java -jar). Other processes are never touched.
#
# Services: identity, product-catalog, api-gateway (default: all, started in that order).
#
# Configuration comes from the environment, optionally loaded from <repo>/.env (see .env.example).
# Script-specific variables:
#   LOG_DIR             where JSON logs go (default <repo>/logs; Alloy ships *.log from here)
#   IDENTITY_PROFILES   Spring profiles for identity (default "local" = bootstrap admin; "" in prod)
#   JAVA_OPTS           extra JVM options for every service (e.g. "-Xmx512m")
#   START_TIMEOUT       seconds to wait for each service to listen (default 120)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ -f "$ROOT/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/.env"
  set +a
fi

export LOG_DIR="${LOG_DIR:-$ROOT/logs}"
[[ "$LOG_DIR" = /* ]] || LOG_DIR="$ROOT/$LOG_DIR"   # absolute, so it doesn't depend on cwd
RUN_DIR="$ROOT/.run"
START_TIMEOUT="${START_TIMEOUT:-120}"
IDENTITY_PROFILES="${IDENTITY_PROFILES-local}"
JAVA_OPTS="${JAVA_OPTS:-}"

ALL_SERVICES=(identity product-catalog api-gateway)

# Pretty-prints JSON log lines. "brief" = only WARN/ERROR with the root cause (startup failures).
PRETTY="$(cat <<'PY'
import json, sys
brief = len(sys.argv) > 1 and sys.argv[1] == "brief"
for line in sys.stdin:
    try:
        d = json.loads(line)
    except ValueError:
        if not brief:
            print(line, end="")
        continue
    level = d.get("level", "")
    if brief and level not in ("WARN", "ERROR"):
        continue
    extra = " ".join(f"{k}={d[k]}" for k in ("method", "path", "status", "durationMs") if k in d)
    print(f'{d.get("timestamp", "")} {level:5} [{d.get("correlationId", "-")}] '
          f'{d.get("message", "")} {extra}'.rstrip(), flush=True)
    ex = d.get("exception")
    if ex and brief:
        causes = [l.strip() for l in ex.get("stackTrace", "").splitlines() if l.strip().startswith("Caused by")]
        print("    " + (causes[-1] if causes else f'{ex.get("type")}: {ex.get("message")}'), flush=True)
    elif ex:
        print(ex.get("stackTrace", ""), flush=True)
PY
)"

port_of() {
  case "$1" in
    api-gateway) echo 8081 ;;
    identity) echo 8083 ;;
    product-catalog) echo 8084 ;;
    *) echo "unknown service: $1" >&2; exit 2 ;;
  esac
}
jar_of() { echo "$ROOT/$1/target/$1-0.0.1-SNAPSHOT.jar"; }
pid_file() { echo "$RUN_DIR/$1.pid"; }
console_file() { echo "$LOG_DIR/$1.console.out"; }   # not *.log, so Alloy doesn't ship it twice

if [[ -t 1 ]]; then G=$'\e[32m'; R=$'\e[31m'; Y=$'\e[33m'; B=$'\e[1m'; N=$'\e[0m'; else G= R= Y= B= N=; fi
ok()   { echo "  ${G}✔${N} $*"; }
warn() { echo "  ${Y}!${N} $*"; }
fail() { echo "  ${R}✘${N} $*"; }

port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

running_pid() {
  local f; f="$(pid_file "$1")"
  [[ -f "$f" ]] || return 1
  local pid; pid="$(cat "$f")"
  if kill -0 "$pid" 2>/dev/null; then echo "$pid"; else rm -f "$f"; return 1; fi
}

# Parses "[all|service...] [flags]" into SVCS (in start order) and the flag variables.
# Runs in the main shell, so an unknown name or flag stops the script with exit code 2.
SVCS=()
OPT_BUILD=0
OPT_FORCE=0
parse_args() {
  local allowed_flags="$1"; shift
  local wanted=() a s
  OPT_BUILD=0; OPT_FORCE=0
  for a in "$@"; do
    case "$a" in
      --build|--force)
        [[ " $allowed_flags " == *" $a "* ]] || usage_error "option $a is not valid for this command"
        [[ "$a" == --build ]] && OPT_BUILD=1 || OPT_FORCE=1 ;;
      all) wanted=("${ALL_SERVICES[@]}") ;;
      -*) usage_error "unknown option: $a" ;;
      *)
        [[ " ${ALL_SERVICES[*]} " == *" $a "* ]] || usage_error "unknown service: $a (services: ${ALL_SERVICES[*]}, or all)"
        wanted+=("$a") ;;
    esac
  done
  [[ ${#wanted[@]} -gt 0 ]] || wanted=("${ALL_SERVICES[@]}")
  # keep dependency order (identity, product-catalog, api-gateway) and drop duplicates
  SVCS=()
  for s in "${ALL_SERVICES[@]}"; do
    [[ " ${wanted[*]} " == *" $s "* ]] && SVCS+=("$s")
  done
  return 0
}

usage_error() {
  echo "${R}error:${N} $*" >&2
  echo "run '$0' without arguments for usage" >&2
  exit 2
}

check_infra() {
  echo "${B}Infrastructure${N}"
  local name port missing=0
  for entry in "PostgreSQL:5432" "Redis:6379" "Kafka:9092" "Keycloak:8080" "MinIO:9000" "Loki (optional):3100"; do
    name="${entry%:*}"; port="${entry##*:}"
    if port_open "$port"; then ok "$name :$port"
    elif [[ "$name" == *optional* ]]; then warn "$name :$port not reachable (logs still go to files)"
    else fail "$name :$port not reachable"; missing=1; fi
  done
  if [[ $missing -eq 1 ]]; then
    warn "some dependencies are down; services may fail to start or fail requests"
  fi
}

build() {
  echo "${B}Building${N} (mvn -q clean package -DskipTests)"
  mvn -q -f "$ROOT/pom.xml" clean package -DskipTests
  ok "build finished"
}

# Compiles without touching the jars that running services execute from.
verify_build() {
  echo "${B}Checking the build${N} (mvn -q compile; running services are not touched)"
  if ! mvn -q -f "$ROOT/pom.xml" compile; then
    fail "build failed: nothing was stopped"
    exit 1
  fi
  ok "code compiles"
}

start_one() {
  local svc="$1" port jar pid profiles=""
  port="$(port_of "$svc")"; jar="$(jar_of "$svc")"

  if pid="$(running_pid "$svc")"; then ok "$svc already running (pid $pid, :$port)"; return 0; fi
  if port_open "$port"; then
    fail "$svc: port $port is already in use by another process (IDE run? 'lsof -i :$port')"; return 1
  fi
  [[ -f "$jar" ]] || { fail "$svc: $jar not found (run with --build)"; return 1; }

  [[ "$svc" == identity && -n "$IDENTITY_PROFILES" ]] && profiles="$IDENTITY_PROFILES"

  # shellcheck disable=SC2086
  SPRING_PROFILES_ACTIVE="${profiles:-${SPRING_PROFILES_ACTIVE:-}}" \
    nohup java $JAVA_OPTS -jar "$jar" >"$(console_file "$svc")" 2>&1 &
  pid=$!
  echo "$pid" >"$(pid_file "$svc")"

  local waited=0
  while ! port_open "$port"; do
    if ! kill -0 "$pid" 2>/dev/null; then
      rm -f "$(pid_file "$svc")"
      fail "$svc exited during startup. Warnings/errors (full output: $(console_file "$svc")):"
      tail -n 200 "$(console_file "$svc")" | python3 -c "$PRETTY" brief | tail -n 12 | sed 's/^/      /'
      return 1
    fi
    if (( waited >= START_TIMEOUT )); then
      fail "$svc did not open :$port within ${START_TIMEOUT}s (still starting? see $(console_file "$svc"))"
      return 1
    fi
    sleep 1; waited=$((waited + 1))
  done
  ok "$svc started (pid $pid, :$port, ${waited}s${profiles:+, profile $profiles})"
}

# PID listening on the service port, if that process is this project's service (its command line
# mentions <repo>/<service>, as a jar, target/classes or spring-boot:run do). Otherwise nothing.
foreign_pid() {
  local svc="$1" pid cmd
  command -v lsof >/dev/null || return 1
  pid="$(lsof -ti "tcp:$(port_of "$svc")" -sTCP:LISTEN 2>/dev/null | head -n 1)"
  [[ -n "$pid" ]] || return 1
  cmd="$(ps -o command= -p "$pid" 2>/dev/null || true)"
  [[ "$cmd" == *"$ROOT/$svc"* || "$cmd" == *"$svc-0.0.1-SNAPSHOT.jar"* ]] || return 1
  echo "$pid"
}

terminate() {
  local svc="$1" pid="$2" waited=0
  kill "$pid" 2>/dev/null || true
  while kill -0 "$pid" 2>/dev/null; do
    if (( waited >= 30 )); then warn "$svc still running after 30s, sending SIGKILL"; kill -9 "$pid" 2>/dev/null || true; break; fi
    sleep 1; waited=$((waited + 1))
  done
}

stop_one() {
  local svc="$1" pid port
  port="$(port_of "$svc")"
  if pid="$(running_pid "$svc")"; then
    terminate "$svc" "$pid"
    rm -f "$(pid_file "$svc")"
    ok "$svc stopped"
    return 0
  fi
  if ! port_open "$port"; then
    echo "  - $svc not running"
    return 0
  fi
  if (( OPT_FORCE )); then
    if pid="$(foreign_pid "$svc")"; then
      terminate "$svc" "$pid"
      ok "$svc stopped (was started outside this script, pid $pid)"
      return 0
    fi
    warn "$svc: :$port is used by a process that is not this project's $svc (left untouched)"
    return 1
  fi
  warn "$svc: :$port is in use by a process not started by this script (use --force to stop it)"
  return 1
}

cmd_start() {
  parse_args "--build" "$@"
  mkdir -p "$LOG_DIR" "$RUN_DIR"
  check_infra

  local s
  if (( OPT_BUILD )); then
    # 'clean package' rewrites the jars; never do that under a running service
    for s in "${ALL_SERVICES[@]}"; do
      if running_pid "$s" >/dev/null || foreign_pid "$s" >/dev/null; then
        usage_error "$s is running; use 'restart --build' (or stop it first) to rebuild"
      fi
    done
    build
  else
    for s in "${SVCS[@]}"; do [[ -f "$(jar_of "$s")" ]] || { warn "$(jar_of "$s") missing"; build; break; }; done
  fi

  echo "${B}Starting${N} ${SVCS[*]} (logs: $LOG_DIR)"
  local failed=0
  for s in "${SVCS[@]}"; do start_one "$s" || failed=1; done
  echo
  cmd_status
  return $failed
}

cmd_stop() {
  parse_args "--force" "$@"
  echo "${B}Stopping${N} ${SVCS[*]}"
  # reverse order: gateway first, so it stops taking traffic before its backends go away
  local i failed=0
  for (( i=${#SVCS[@]}-1; i>=0; i-- )); do stop_one "${SVCS[$i]}" || failed=1; done
  return $failed
}

cmd_restart() {
  parse_args "--build --force" "$@"
  local targets=("${SVCS[@]}") build_requested=$OPT_BUILD force=$OPT_FORCE
  (( build_requested )) && verify_build
  local stop_args=("${targets[@]}"); (( force )) && stop_args+=(--force)
  if ! cmd_stop "${stop_args[@]}"; then
    fail "not every service could be stopped; not starting (see above)"
    return 1
  fi
  local start_args=("${targets[@]}")
  if (( build_requested )); then
    # everything we are about to rebuild is stopped; services left running keep their jars
    local s
    for s in "${ALL_SERVICES[@]}"; do
      if running_pid "$s" >/dev/null || foreign_pid "$s" >/dev/null; then
        warn "$s still running, so the rebuild is skipped (restart all with --build to rebuild everything)"
        build_requested=0
      fi
    done
    (( build_requested )) && build
  fi
  OPT_BUILD=0
  cmd_start "${start_args[@]}"
}

cmd_status() {
  echo "${B}Status${N}"
  printf "  %-16s %-8s %-6s %s\n" SERVICE PID PORT STATE
  local s pid port state
  for s in "${ALL_SERVICES[@]}"; do
    port="$(port_of "$s")"
    if pid="$(running_pid "$s")"; then
      if port_open "$port"; then state="${G}up${N}"; else state="${Y}starting${N}"; fi
    else
      pid="-"
      if port_open "$port"; then state="${Y}up (not started by this script)${N}"; else state="${R}down${N}"; fi
    fi
    printf "  %-16s %-8s %-6s %s\n" "$s" "$pid" "$port" "$state"
  done
  echo "  gateway: http://localhost:8081   logs: $LOG_DIR"
}

cmd_logs() {
  local svc="${1:-}"; shift || true
  [[ -n "$svc" ]] || { echo "usage: $0 logs <service> [-f]" >&2; exit 2; }
  port_of "$svc" >/dev/null
  local name="$svc"; [[ "$svc" == identity ]] && name="identity-service"  # spring.application.name
  local file="$LOG_DIR/$name.log"
  [[ -f "$file" ]] || { echo "no log file yet: $file" >&2; exit 1; }
  if [[ "${1:-}" == -f ]]; then tail -n 50 -f "$file" | python3 -u -c "$PRETTY"
  else tail -n 100 "$file" | python3 -c "$PRETTY"; fi
}

case "${1:-}" in
  start) shift; cmd_start "$@" ;;
  stop) shift; cmd_stop "$@" ;;
  restart) shift; cmd_restart "$@" ;;
  status) cmd_status ;;
  logs) shift; cmd_logs "$@" ;;
  *) sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
