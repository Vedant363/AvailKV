#!/bin/bash

# docker-avail.sh — AvailKV Docker Cluster Manager
# Asks for node count, generates docker-compose.yml, starts cluster,
# then drops into the same command REPL as avail.sh.

BASE_PORT=8080
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
RESET='\033[0m'

COMPOSE_FILE="docker-compose.generated.yml"
SESSION_FILE=".docker-session"

# ════════════════════════════════════════════════════════════════════
# GENERATE docker-compose.yml dynamically based on NODE_COUNT
# ════════════════════════════════════════════════════════════════════

generate_compose() {
  local count=$1
  cat > "$COMPOSE_FILE" << EOF
version: "3.9"

services:
EOF

  for i in $(seq 1 "$count"); do
    local port=$((BASE_PORT + i))
    local node_id="node$i"

    # Build peer URLs — every node except self
    local peer_urls=""
    local peer_ids=""
    for j in $(seq 1 "$count"); do
      if [ "$j" -ne "$i" ]; then
        local peer_port=$((BASE_PORT + j))
        peer_urls="${peer_urls}http://node${j}:${peer_port},"
        peer_ids="${peer_ids}node${j},"
      fi
    done
    # Strip trailing comma
    peer_urls="${peer_urls%,}"
    peer_ids="${peer_ids%,}"

    cat >> "$COMPOSE_FILE" << EOF

  $node_id:
    build: .
    container_name: availkv-$node_id
    environment:
      NODE_ID: $node_id
      NODE_PORT: "$port"
      PEER_URLS: "$peer_urls"
      PEER_IDS: "$peer_ids"
      WAL_PATH: "/app/logs/wal.txt"
      OLLAMA_URL: "http://ollama:11434"
      OLLAMA_MODEL: "gemma2:2b"
    ports:
      - "$port:$port"
    volumes:
      - ${node_id}-logs:/app/logs
    networks:
      - availkv-net
    depends_on:
      - ollama
    restart: on-failure
EOF
  done

  # Ollama service
  cat >> "$COMPOSE_FILE" << EOF

  ollama:
    image: ollama/ollama:latest
    container_name: availkv-ollama
    ports:
      - "11434:11434"
    volumes:
      - ollama-data:/root/.ollama
    networks:
      - availkv-net
    restart: on-failure

volumes:
EOF

  for i in $(seq 1 "$count"); do
    echo "  node${i}-logs:" >> "$COMPOSE_FILE"
  done
  echo "  ollama-data:" >> "$COMPOSE_FILE"

  cat >> "$COMPOSE_FILE" << EOF

networks:
  availkv-net:
    driver: bridge
EOF

  echo -e "${GREEN}Generated $COMPOSE_FILE for $count nodes.${RESET}"
}

# ════════════════════════════════════════════════════════════════════
# STARTUP — session detection + node count selection
# ════════════════════════════════════════════════════════════════════

print_banner() {
  echo ""
  echo -e "${BOLD}╔══════════════════════════════════╗${RESET}"
  echo -e "${BOLD}║  AvailKV — Docker Cluster CLI    ║${RESET}"
  echo -e "${BOLD}╚══════════════════════════════════╝${RESET}"
  echo ""
}

ask_node_count() {
  echo -e "How many nodes? ${CYAN}(3 / 5 / 7 / 9 / 11)${RESET}"
  while true; do
    read -rp "> " NODE_COUNT
    case "$NODE_COUNT" in
      3|5|7|9|11)
        echo "$NODE_COUNT" > "$SESSION_FILE"
        break
        ;;
      *) echo -e "${RED}Please enter an odd number between 3 and 11.${RESET}" ;;
    esac
  done
}

choose_startup_mode() {
  print_banner

  # Check if a previous session + compose file exist
  if [ -f "$SESSION_FILE" ] && [ -f "$COMPOSE_FILE" ]; then
    local prev
    prev=$(cat "$SESSION_FILE")
    local running
    running=$(docker compose -f "$COMPOSE_FILE" ps --status running --services 2>/dev/null | grep -c "node" || echo 0)

    echo -e "${CYAN}Previous session detected — $prev nodes.${RESET}"
    echo -e "${CYAN}Containers currently running: $running/$prev${RESET}"
    echo ""
    echo -e "  ${BOLD}1)${RESET} Continue with same setup ($prev nodes)"
    echo -e "  ${BOLD}2)${RESET} Start fresh — choose new node count"
    echo ""
    while true; do
      read -rp "Choose (1 or 2): " choice
      case "$choice" in
        1)
          NODE_COUNT="$prev"
          echo -e "${GREEN}Resuming with $NODE_COUNT nodes.${RESET}"
          return
          ;;
        2)
          # Tear down old cluster before starting new one
          echo -e "${YELLOW}Stopping previous cluster...${RESET}"
          docker compose -f "$COMPOSE_FILE" down 2>/dev/null
          ask_node_count
          return
          ;;
        *) echo -e "${RED}Please enter 1 or 2.${RESET}" ;;
      esac
    done
  else
    ask_node_count
  fi
}

start_cluster() {
  generate_compose "$NODE_COUNT"
  echo ""
  echo -e "${BOLD}Starting $NODE_COUNT node cluster...${RESET}"
  docker compose -f "$COMPOSE_FILE" up --build -d

  echo ""
  echo -n "Waiting for nodes to be ready"
  local ready=0
  local attempts=0
  while [ "$ready" -lt "$NODE_COUNT" ] && [ "$attempts" -lt 40 ]; do
    ready=0
    for i in $(seq 1 "$NODE_COUNT"); do
      local port=$((BASE_PORT + i))
      local code
      code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 1 "http://localhost:$port/actuator/health" 2>/dev/null)
      [ "$code" == "200" ] && ((ready++))
    done
    echo -n "."
    sleep 2
    ((attempts++))
  done
  echo ""

  if [ "$ready" -eq "$NODE_COUNT" ]; then
    echo -e "${GREEN}All $NODE_COUNT nodes are up!${RESET}"
  else
    echo -e "${YELLOW}$ready/$NODE_COUNT nodes ready. Others may still be starting.${RESET}"
  fi

  # Remind about Ollama model on first run
  if ! docker exec availkv-ollama ollama list 2>/dev/null | grep -q "gemma2"; then
    echo ""
    echo -e "${YELLOW}Ollama model not found. Pull it with:${RESET}"
    echo -e "  ${CYAN}docker exec -it availkv-ollama ollama pull gemma2:2b${RESET}"
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
# COMMANDS — identical to avail.sh except KILL/RESTART use Docker
# ════════════════════════════════════════════════════════════════════

cmd_put() {
  local pair="$1"
  local key="${pair%%=*}"
  local value="${pair#*=}"
  if [ -z "$key" ] || [ -z "$value" ] || [ "$key" == "$value" ]; then
    echo -e "${RED}Usage: PUT key=value${RESET}"; return
  fi
  local leader_url
  leader_url=$(find_leader_url)
  if [ -z "$leader_url" ]; then
    echo -e "${RED}No leader available.${RESET}"; return
  fi
  local resp
  resp=$(curl -s -X PUT "$leader_url/kv/$key" -H "Content-Type: text/plain" -d "$value" --max-time 3)
  echo -e "${GREEN}PUT${RESET} $key = $value  →  $resp"
}

cmd_get() {
  local key="$1"
  if [ -z "$key" ]; then echo -e "${RED}Usage: GET key${RESET}"; return; fi
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
  echo -e "${RED}No alive nodes.${RESET}"
}

cmd_delete() {
  local key="$1"
  if [ -z "$key" ]; then echo -e "${RED}Usage: DELETE key${RESET}"; return; fi
  local leader_url
  leader_url=$(find_leader_url)
  if [ -z "$leader_url" ]; then
    echo -e "${RED}No leader available.${RESET}"; return
  fi
  local resp
  resp=$(curl -s -X DELETE "$leader_url/kv/$key" --max-time 3)
  echo -e "${RED}DELETE${RESET} $key  →  $resp"
}

cmd_systemstatus() {
  echo ""
  local line="--------------------------------"
  echo "$line"
  printf "| %-28s |\n" "Total Nodes : $NODE_COUNT"
  echo "$line"
  for i in $(seq 1 "$NODE_COUNT"); do
    local url
    url=$(node_url "$i")
    if is_alive "$url"; then
      local status
      status=$(curl -s --max-time 2 "$url/status" 2>/dev/null)
      local role
      role=$(echo "$status" | awk -F' | ' '{print $2}' | xargs)
      printf "| %-22s %s |\n" "Node $i ($role) :" "✅"
    else
      printf "| %-22s %s |\n" "Node $i :" "❌"
    fi
  done
  echo "$line"
  echo ""
}

cmd_listall() {
  local leader_url
  leader_url=$(find_leader_url)
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
  for i in $(seq 1 "$NODE_COUNT"); do
    local url
    url=$(node_url "$i")
    if [ "$url" != "$leader_url" ] && is_alive "$url"; then
      local wal
      wal=$(curl -s --max-time 3 "$url/wal")
      if [ -n "$wal" ]; then
        echo -e "${YELLOW}WAL from fallback node$i:${RESET}"
        echo ""
        echo "$wal"
        return
      fi
    fi
  done
  echo -e "${RED}No alive nodes. Cannot fetch WAL.${RESET}"
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
  if [ "${target^^}" == "ALL" ]; then
    echo -e "${RED}Stopping all node containers...${RESET}"
    for i in $(seq 1 "$NODE_COUNT"); do
      docker compose -f "$COMPOSE_FILE" stop "node$i"
    done
    echo "Done."
    return
  fi
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
    echo -e "${RED}Stopping container: $leader_id${RESET}"
    docker compose -f "$COMPOSE_FILE" stop "$leader_id"
    return
  fi
  if [[ "$target" =~ ^[0-9]+$ ]] && [ "$target" -ge 1 ] && [ "$target" -le "$NODE_COUNT" ]; then
    echo -e "${RED}Stopping container: node$target${RESET}"
    docker compose -f "$COMPOSE_FILE" stop "node$target"
  else
    echo -e "${RED}Usage: KILL <n> | KILL LEADER | KILL ALL${RESET}"
  fi
}

cmd_restart() {
  local target="$1"
  if [[ "$target" =~ ^[0-9]+$ ]] && [ "$target" -ge 1 ] && [ "$target" -le "$NODE_COUNT" ]; then
    echo -e "${CYAN}Restarting container: node$target${RESET}"
    docker compose -f "$COMPOSE_FILE" restart "node$target"
    echo -e "${GREEN}node$target restarted${RESET}"
  else
    echo -e "${RED}Usage: RESTART <node_number>${RESET}"
  fi
}

cmd_ai() {
  local arg="$1"
  local target_url=""
  local question=""
  local first_token
  first_token=$(echo "$arg" | awk '{print $1}')
  if [[ "$first_token" =~ ^[0-9]+$ ]]; then
    local node_index="$first_token"
    if [ "$node_index" -lt 1 ] || [ "$node_index" -gt "$NODE_COUNT" ]; then
      echo -e "${RED}Node $node_index does not exist.${RESET}"; return
    fi
    target_url=$(node_url "$node_index")
    if ! is_alive "$target_url"; then
      echo -e "${RED}node$node_index is not alive.${RESET}"; return
    fi
    question=$(echo "$arg" | cut -d' ' -f2-)
    echo -e "${CYAN}Asking node$node_index:${RESET} $question"
  else
    target_url=$(find_leader_url)
    if [ -z "$target_url" ]; then
      echo -e "${RED}No leader available.${RESET}"; return
    fi
    question="$arg"
    echo -e "${CYAN}Asking leader ($target_url):${RESET} $question"
  fi
  if [ -z "$question" ]; then
    echo -e "${RED}Usage: AI <question>  or  AI <n> <question>${RESET}"; return
  fi
  echo ""
  curl -s -X POST "$target_url/ask" \
    -H "Content-Type: text/plain" \
    -d "$question" \
    --max-time 60
  echo ""
}

cmd_logs() {
  local target="$1"
  if [[ "$target" =~ ^[0-9]+$ ]] && [ "$target" -ge 1 ] && [ "$target" -le "$NODE_COUNT" ]; then
    echo -e "${CYAN}Logs for node$target:${RESET}"
    docker compose -f "$COMPOSE_FILE" logs --tail=50 "node$target"
  else
    echo -e "${RED}Usage: LOGS <node_number>${RESET}"
  fi
}

cmd_help() {
  echo ""
  echo -e "${BOLD}Available commands:${RESET}"
  echo ""
  echo -e "  ${CYAN}PUT key=value${RESET}               Write a key to the leader"
  echo -e "  ${CYAN}GET key${RESET}                     Read a key from any alive node"
  echo -e "  ${CYAN}DELETE key${RESET}                  Delete a key"
  echo -e "  ${CYAN}SYSTEMSTATUS${RESET}                Show which nodes are up/down"
  echo -e "  ${CYAN}LISTALL${RESET}                     Print full WAL"
  echo -e "  ${CYAN}LEADER${RESET}                      Show current leader"
  echo -e "  ${CYAN}KILL <n>${RESET}                    Stop container node n"
  echo -e "  ${CYAN}KILL LEADER${RESET}                 Stop the leader container"
  echo -e "  ${CYAN}KILL ALL${RESET}                    Stop all node containers"
  echo -e "  ${CYAN}RESTART <n>${RESET}                 Restart container node n"
  echo -e "  ${CYAN}AI <question>${RESET}               Ask leader an AI diagnostic question"
  echo -e "  ${CYAN}AI <n> <question>${RESET}           Ask node n specifically"
  echo -e "  ${CYAN}LOGS <n>${RESET}                    Tail Spring Boot logs of node n"
  echo -e "  ${CYAN}HELP${RESET}                        Show this list"
  echo -e "  ${CYAN}EXIT${RESET}                        Exit CLI (containers keep running)"
  echo ""
}

# ════════════════════════════════════════════════════════════════════
# MAIN
# ════════════════════════════════════════════════════════════════════

# Check Docker is available
if ! command -v docker &>/dev/null; then
  echo -e "${RED}Docker not found. Please install Docker Desktop.${RESET}"
  exit 1
fi

choose_startup_mode
start_cluster

echo ""
echo -e "Type ${CYAN}HELP${RESET} to see all commands."
echo ""

while true; do
  read -rp "availkv-docker> " input
  input=$(echo "$input" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
  [ -z "$input" ] && continue

  cmd=$(echo "$input" | awk '{print toupper($1)}')
  arg=$(echo "$input" | cut -d' ' -f2-)
  [ "$arg" == "$input" ] && arg=""

  case "$cmd" in
    PUT)          cmd_put "$arg" ;;
    GET)          cmd_get "$arg" ;;
    DELETE)       cmd_delete "$arg" ;;
    SYSTEMSTATUS) cmd_systemstatus ;;
    LISTALL)      cmd_listall ;;
    LEADER)       cmd_leader ;;
    KILL)         cmd_kill "$(echo "$arg" | awk '{print toupper($1)}')" ;;
    RESTART)      cmd_restart "$arg" ;;
    AI)           cmd_ai "$arg" ;;
    LOGS)         cmd_logs "$arg" ;;
    HELP)         cmd_help ;;
    EXIT|QUIT)
      echo "Exiting CLI. Containers are still running."
      echo -e "To stop everything: ${CYAN}docker compose -f $COMPOSE_FILE down${RESET}"
      exit 0
      ;;
    *)
      echo -e "${RED}Unknown command: $cmd${RESET} — type HELP"
      ;;
  esac
done