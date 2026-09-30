#!/usr/bin/env bash
# Waits for a GitHub Actions run to finish and prints a per-step summary.
set -uo pipefail
RUN_ID="$1"
cd /workspace/b0fae9de-dcfd-458a-980a-741f9b2059a8/sessions/agent_cd5a6871-1cbf-4dee-822a-62c8f681f754

for _ in $(seq 1 120); do
  state=$(gh run view "$RUN_ID" --json status,conclusion \
    --jq '.status + " " + (.conclusion // "pending")' 2>/dev/null)
  case "$state" in
    completed*) break ;;
  esac
  sleep 25
done

echo "FINAL: $state"
echo "--- steps ---"
gh run view "$RUN_ID" --json jobs \
  --jq '.jobs[].steps[] | (.conclusion // .status) + "  " + .name'
