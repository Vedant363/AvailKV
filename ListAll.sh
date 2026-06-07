#!/bin/bash

# ListAll — fetches and prints the WAL from the best available node

NODE_URLS=(
  "http://localhost:8081"
  "http://localhost:8082"
  "http://localhost:8083"
)

is_alive() {
  local code
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 "$1/actuator/health")
  [ "$code" == "200" ]
}

fetch_wal() {
  curl -s --max-time 3 "$1/wal"
}

# ── Step 1: find leader URL ─────────────────────────────────────────
LEADER_URL=""
for url in "${NODE_URLS[@]}"; do
  if is_alive "$url"; then
    status=$(curl -s --max-time 2 "$url/status")
    # /status returns: "node1 | LEADER | term=3 | leader=node2"
    # Use sed instead of grep -P (works on Windows Git Bash)
    leader_id=$(echo "$status" | sed 's/.*leader=//;s/ .*//')

    if [ -n "$leader_id" ] && [ "$leader_id" != "null" ]; then
      for candidate in "${NODE_URLS[@]}"; do
        if is_alive "$candidate"; then
          node_status=$(curl -s --max-time 2 "$candidate/status" 2>/dev/null)
          node_id=$(echo "$node_status" | sed 's/ |.*//')
          if [ "$node_id" == "$leader_id" ]; then
            LEADER_URL="$candidate"
            break
          fi
        fi
      done
    fi
    break
  fi
done

# ── Step 2: try leader first ────────────────────────────────────────
if [ -n "$LEADER_URL" ]; then
  wal=$(fetch_wal "$LEADER_URL")
  if [ -n "$wal" ]; then
    echo "WAL fetched from leader ($LEADER_URL):"
    echo ""
    echo "$wal"
    exit 0
  fi
fi

# ── Step 3: try each alive follower ────────────────────────────────
for url in "${NODE_URLS[@]}"; do
  if [ "$url" == "$LEADER_URL" ]; then continue; fi
  if is_alive "$url"; then
    wal=$(fetch_wal "$url")
    if [ -n "$wal" ]; then
      echo "Leader unavailable. WAL fetched from fallback node ($url):"
      echo ""
      echo "$wal"
      exit 0
    fi
  fi
done

# ── Step 4: nothing worked ──────────────────────────────────────────
echo "ERROR: No alive nodes found. Cannot fetch WAL."
exit 1