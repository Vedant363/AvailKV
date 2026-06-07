#!/bin/bash


# ── Config ───────────────────────────────────────────────────────────
BASE_PORT=8080          # nodes get ports BASE_PORT+1, BASE_PORT+2, ...
JAR_PATH="target/availkv-0.0.1-SNAPSHOT.jar"   # adjust if needed
LOG_DIR="logs"
PID_DIR=".pids"

# ── Colors ───────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
RESET='\033[0m'

mkdir -p "$LOG_DIR" "$PID_DIR"

# ════════════════════════════════════════════════════════════════════
# SETUP — ask for node count, build peer URLs, start all nodes
# ════════════════════════════════════════════════════════════════════

choose_node_count() {
  echo ""
  echo -e "${BOLD}╔══════════════════════════════════╗${RESET}"
  echo -e "${BOLD}║     AvailKV Cluster Manager      ║${RESET}"
  echo -e "${BOLD}╚══════════════════════════════════╝${RESET}"
  echo ""
  echo -e "How many nodes? ${CYAN}(3 / 5 / 7 / 9 / 11)${RESET}"
  while true; do
    read -rp "> " NODE_COUNT
    case "$NODE_COUNT" in
      3|5|7|9|11) break ;;
      *) echo -e "${RED}Please enter an odd number between 3 and 11.${RESET}" ;;
    esac
  done
}

# Build arrays of ports, node IDs, and peer URL strings for each node
build_config() {
  PORTS=()
  NODE_IDS=()
  for i in $(seq 1 "$NODE_COUNT"); do
    PORTS+=($((BASE_PORT + i)))
    NODE_IDS+=("node$i")
  done
}

# For a given node index, return comma-separated URLs of all OTHER nodes
peer_urls_for() {
  local my_index=$1
  local peers=()
  for i in $(seq 1 "$NODE_COUNT"); do
    if [ "$i" -ne "$my_index" ]; then
      peers+=("http://localhost:$((BASE_PORT + i))")
    fi
  done
  echo "$(IFS=,; echo "${peers[*]}")"
}

# For a given node index, return comma-separated IDs of all OTHER nodes
peer_ids_for() {
  local my_index=$1
  local peers=()
  for i in $(seq 1 "$NODE_COUNT"); do
    if [ "$i" -ne "$my_index" ]; then
      peers+=("node$i")
    fi
  done
  echo "$(IFS=,; echo "${peers[*]}")"
}

start_node() {
  local index=$1   # 1-based
  local port="${PORTS[$((index-1))]}"
  local node_id="node$index"
  local peer_urls
  peer_urls=$(peer_urls_for "$index")
  local peer_ids
  peer_ids=$(peer_ids_for "$index")
  local log_file="$LOG_DIR/$node_id.log"
  local pid_file="$PID_DIR/$node_id.pid"

  if [ -f "$pid_file" ]; then
    local existing_pid
    existing_pid=$(cat "$pid_file")
    if kill -0 "$existing_pid" 2>/dev/null; then
      echo -e "${YELLOW}$node_id is already running (PID $existing_pid)${RESET}"
      return
    fi
  fi

  NODE_ID="$node_id" \
  NODE_PORT="$port" \
  PEER_URLS="$peer_urls" \
  PEER_IDS="$peer_ids" \
  WAL_PATH="$LOG_DIR/${node_id}_wal.txt" \
    java -jar "$JAR_PATH" \
    > "$log_file" 2>&1 &

  echo $! > "$pid_file"
  echo -e "  ${GREEN}✅ $node_id${RESET} started on port $port (PID $!)"
}

start_all_nodes() {
  echo ""
  echo -e "${BOLD}Starting $NODE_COUNT nodes...${RESET}"
  for i in $(seq 1 "$NODE_COUNT"); do
    start_node "$i"
  done

  # Wait for all nodes to be ready
  echo ""
  echo -n "Waiting for nodes to be ready"
  local ready=0
  local attempts=0
  while [ "$ready" -lt "$NODE_COUNT" ] && [ "$attempts" -lt 30 ]; do
    ready=0
    for i in $(seq 1 "$NODE_COUNT"); do
      local port="${PORTS[$((i-1))]}"
      local code
      code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 1 "http://localhost:$port/actuator/health" 2>/dev/null)
      [ "$code" == "200" ] && ((ready++))
    done
    echo -n "."
    sleep 1
    ((attempts++))
  done
  echo ""

  if [ "$ready" -eq "$NODE_COUNT" ]; then
    echo -e "${GREEN}All $NODE_COUNT nodes are up!${RESET}"
  else
    echo -e "${YELLOW}$ready/$NODE_COUNT nodes ready (others may still be starting)${RESET}"
  fi
}

# ════════════════════════════════════════════════════════════════════
# HELPERS
# ════════════════════════════════════════════════════════════════════

node_url() {
  echo "http://localhost:$((BASE_PORT + $1))"
}

is_alive() {
  local code
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 "$1/actuator/health" 2>/dev/null)
  [ "$code" == "200" ]
}

# Returns the URL of the current leader, or empty string if no leader found
find_leader_url() {
  for i in $(seq 1 "$NODE_COUNT"); do
    local url
    url=$(node_url "$i")
    if is_alive "$url"; then
      local status
      status=$(curl -s --max-time 2 "$url/status" 2>/dev/null)
      local leader_id
      leader_id=$(echo "$status" | sed 's/.*leader=//;s/ .*//')
      if [ -n "$leader_id" ] && [ "$leader_id" != "null" ] && [ "$leader_id" != "NONE" ]; then
        # Find which node index matches this leader_id
        for j in $(seq 1 "$NODE_COUNT"); do
          if [ "node$j" == "$leader_id" ]; then
            local lurl
            lurl=$(node_url "$j")
            if is_alive "$lurl"; then
              echo "$lurl"
              return
            fi
          fi
        done
      fi
    fi
  done
  echo ""
}

# ════════════════════════════════════════════════════════════════════
# COMMANDS
# ════════════════════════════════════════════════════════════════════

cmd_put() {
  # PUT name=vedant  →  key=name value=vedant
  local pair="$1"
  local key="${pair%%=*}"
  local value="${pair#*=}"
  if [ -z "$key" ] || [ -z "$value" ] || [ "$key" == "$value" ]; then
    echo -e "${RED}Usage: PUT key=value${RESET}"; return
  fi
  local leader_url
  leader_url=$(find_leader_url)
  if [ -z "$leader_url" ]; then
    echo -e "${RED}No leader available. Cannot write.${RESET}"; return
  fi
  local resp
  resp=$(curl -s -X PUT "$leader_url/kv/$key" \
    -H "Content-Type: text/plain" -d "$value" --max-time 3)
  echo -e "${GREEN}PUT${RESET} $key = $value  →  $resp"
}

cmd_get() {
  local key="$1"
  if [ -z "$key" ]; then echo -e "${RED}Usage: GET key${RESET}"; return; fi
  # Reads can go to any alive node
  for i in $(seq 1 "$NODE_COUNT"); do
    local url
    url=$(node_url "$i")
    if is_alive "$url"; then
      local resp
      resp=$(curl -s --max-time 3 "$url/kv/$key")
      if [ -n "$resp" ]; then
        echo -e "${CYAN}GET${RESET} $key = $resp"
      else
        echo -e "${YELLOW}$key not found${RESET}"
      fi
      return
    fi
  done
  echo -e "${RED}No alive nodes to read from.${RESET}"
}

cmd_delete() {
  local key="$1"
  if [ -z "$key" ]; then echo -e "${RED}Usage: DELETE key${RESET}"; return; fi
  local leader_url
  leader_url=$(find_leader_url)
  if [ -z "$leader_url" ]; then
    echo -e "${RED}No leader available. Cannot delete.${RESET}"; return
  fi
  local resp
  resp=$(curl -s -X DELETE "$leader_url/kv/$key" --max-time 3)
  echo -e "${RED}DELETE${RESET} $key  →  $resp"
}

cmd_systemstatus() {
  echo ""
  local width=32
  local line
  line=$(printf '%*s' "$width" | tr ' ' '-')
  echo "$line"
  printf "| %-28s |\n" "Total Nodes : $NODE_COUNT"
  echo "$line"
  for i in $(seq 1 "$NODE_COUNT"); do
    local url
    url=$(node_url "$i")
    local label="Node $i"
    if is_alive "$url"; then
      # Also fetch role
      local status
      status=$(curl -s --max-time 2 "$url/status" 2>/dev/null)
      local role
      role=$(echo "$status" | awk -F' | ' '{print $2}' | xargs)
      printf "| %-22s %s |\n" "$label ($role) :" "✅"
    else
      printf "| %-22s %s |\n" "$label :" "❌"
    fi
  done
  echo "$line"
  echo ""
}

cmd_listall() {
  local leader_url
  leader_url=$(find_leader_url)

  # Try leader first
  if [ -n "$leader_url" ]; then
    local wal
    wal=$(curl -s --max-time 3 "$leader_url/wal")
    if [ -n "$wal" ]; then
      echo -e "${GREEN}WAL from leader ($leader_url):${RESET}"
      echo ""
      echo "$wal"
      return
    fi
  fi

  # Fallback to any alive node
  for i in $(seq 1 "$NODE_COUNT"); do
    local url
    url=$(node_url "$i")
    if [ "$url" != "$leader_url" ] && is_alive "$url"; then
      local wal
      wal=$(curl -s --max-time 3 "$url/wal")
      if [ -n "$wal" ]; then
        echo -e "${YELLOW}Leader unavailable. WAL from fallback node$i ($url):${RESET}"
        echo ""
        echo "$wal"
        return
      fi
    fi
  done

  echo -e "${RED}ERROR: No alive nodes found. Cannot fetch WAL.${RESET}"
}

cmd_leader() {
  local leader_url
  leader_url=$(find_leader_url)
  if [ -z "$leader_url" ]; then
    echo -e "${RED}No leader currently elected.${RESET}"; return
  fi
  local status
  status=$(curl -s --max-time 2 "$leader_url/status")
  local leader_id
  leader_id=$(echo "$status" | sed 's/.*leader=//;s/ .*//')
  local term
  term=$(echo "$status" | grep -o 'term=[0-9]*' | head -1)
  echo -e "${GREEN}Current leader: $leader_id${RESET} | $term | $leader_url"
}

cmd_kill() {
  local target="$1"

  # KILL ALL
  if [ "${target^^}" == "ALL" ]; then
    echo -e "${RED}Killing all nodes...${RESET}"
    for i in $(seq 1 "$NODE_COUNT"); do
      _kill_node "$i"
    done
    echo "Done."
    return
  fi

  # KILL LEADER
  if [ "${target^^}" == "LEADER" ]; then
    local leader_url
    leader_url=$(find_leader_url)
    if [ -z "$leader_url" ]; then
      echo -e "${RED}No leader found.${RESET}"; return
    fi
    local status
    status=$(curl -s --max-time 2 "$leader_url/status")
    local leader_id
    leader_id=$(echo "$status" | sed 's/.*leader=//;s/ .*//')
    local index
    index="${leader_id#node}"
    echo -e "${RED}Killing leader: $leader_id${RESET}"
    _kill_node "$index"
    return
  fi

  # KILL <number>
  if [[ "$target" =~ ^[0-9]+$ ]] && [ "$target" -ge 1 ] && [ "$target" -le "$NODE_COUNT" ]; then
    _kill_node "$target"
  else
    echo -e "${RED}Usage: KILL <node_number> | KILL LEADER | KILL ALL${RESET}"
  fi
}

_kill_node() {
  local index=$1
  local pid_file="$PID_DIR/node$index.pid"
  if [ ! -f "$pid_file" ]; then
    echo -e "${YELLOW}node$index has no PID file — may not be running${RESET}"; return
  fi
  local pid
  pid=$(cat "$pid_file")
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid"
    rm -f "$pid_file"
    echo -e "${RED}❌ node$index${RESET} killed (PID $pid)"
  else
    rm -f "$pid_file"
    echo -e "${YELLOW}node$index was not running${RESET}"
  fi
}

cmd_restart() {
  local target="$1"
  if [[ "$target" =~ ^[0-9]+$ ]] && [ "$target" -ge 1 ] && [ "$target" -le "$NODE_COUNT" ]; then
    echo -e "${CYAN}Restarting node$target...${RESET}"
    _kill_node "$target"
    sleep 1
    start_node "$target"
  else
    echo -e "${RED}Usage: RESTART <node_number>${RESET}"
  fi
}

cmd_help() {
  echo ""
  echo -e "${BOLD}Available commands:${RESET}"
  echo ""
  echo -e "  ${CYAN}PUT key=value${RESET}      Write a key to the leader"
  echo -e "  ${CYAN}GET key${RESET}            Read a key from any alive node"
  echo -e "  ${CYAN}DELETE key${RESET}         Delete a key via the leader"
  echo -e "  ${CYAN}SYSTEMSTATUS${RESET}       Show which nodes are up/down with roles"
  echo -e "  ${CYAN}LISTALL${RESET}            Print full WAL (leader first, fallback to follower)"
  echo -e "  ${CYAN}LEADER${RESET}             Show who the current leader is"
  echo -e "  ${CYAN}KILL <n>${RESET}           Kill node n  (e.g. KILL 2)"
  echo -e "  ${CYAN}KILL LEADER${RESET}        Kill whichever node is currently leader"
  echo -e "  ${CYAN}KILL ALL${RESET}           Kill all nodes"
  echo -e "  ${CYAN}RESTART <n>${RESET}        Restart node n  (e.g. RESTART 2)"
  echo -e "  ${CYAN}HELP${RESET}               Show this list"
  echo -e "  ${CYAN}EXIT${RESET}               Kill all nodes and exit"
  echo ""
}

# ════════════════════════════════════════════════════════════════════
# MAIN REPL LOOP
# ════════════════════════════════════════════════════════════════════

# Make sure JAR exists before starting
if [ ! -f "$JAR_PATH" ]; then
  echo -e "${YELLOW}JAR not found at $JAR_PATH — building...${RESET}"
  mvn clean package -q -DskipTests
  if [ $? -ne 0 ]; then
    echo -e "${RED}Build failed. Fix compilation errors and try again.${RESET}"
    exit 1
  fi
fi

choose_node_count
build_config
start_all_nodes

echo ""
echo -e "Type ${CYAN}HELP${RESET} to see all commands."
echo ""

while true; do
  read -rp "availkv> " input
  # Normalize: trim whitespace, uppercase the command word
  input=$(echo "$input" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
  [ -z "$input" ] && continue

  cmd=$(echo "$input" | awk '{print toupper($1)}')
  arg=$(echo "$input" | cut -d' ' -f2-)
  [ "$arg" == "$cmd" ] && arg=""  # no argument given

  case "$cmd" in
    PUT)          cmd_put "$arg" ;;
    GET)          cmd_get "$arg" ;;
    DELETE)       cmd_delete "$arg" ;;
    SYSTEMSTATUS) cmd_systemstatus ;;
    LISTALL)      cmd_listall ;;
    LEADER)       cmd_leader ;;
    KILL)         cmd_kill "$(echo "$arg" | tr '[:lower:]' '[:upper:]')" ;;
    RESTART)      cmd_restart "$arg" ;;
    HELP)         cmd_help ;;
    EXIT|QUIT)
      echo -e "${RED}Shutting down all nodes...${RESET}"
      cmd_kill "ALL"
      echo "Goodbye."
      exit 0
      ;;
    *)
      echo -e "${RED}Unknown command: $cmd${RESET} — type HELP to see available commands"
      ;;
  esac
done