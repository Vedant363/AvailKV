#!/bin/bash

# SystemStatus — checks which AvailKV nodes are currently alive
# Usage: ./SystemStatus.sh
# Customize NODE_URLS below to match your setup

NODE_URLS=(
  "http://localhost:8081"
  "http://localhost:8082"
  "http://localhost:8083"
)

NODE_NAMES=(
  "Node 1"
  "Node 2"
  "Node 3"
)

TOTAL=${#NODE_URLS[@]}

# Check each node by hitting /actuator/health
# -s silent, -o discard body, -w just the HTTP code, --max-time 2s timeout
statuses=()
for i in "${!NODE_URLS[@]}"; do
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 "${NODE_URLS[$i]}/actuator/health")
  if [ "$code" == "200" ]; then
    statuses+=("UP")
  else
    statuses+=("DOWN")
  fi
done

LINE="---------------------------"
echo "$LINE"
printf "| %-22s |\n" "Total Nodes : $TOTAL"
echo "$LINE"
for i in "${!NODE_NAMES[@]}"; do
  if [ "${statuses[$i]}" == "UP" ]; then
    icon="✅"
  else
    icon="❌"
  fi
  printf "| %-21s %s |\n" "${NODE_NAMES[$i]} :" "$icon"
done
echo "$LINE"