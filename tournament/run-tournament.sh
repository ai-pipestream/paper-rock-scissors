#!/usr/bin/env bash
# Language PRNG tournament: launch equal pools of Go, Python, and Java streaming
# clients against a running arena, let them play many matches, then print the
# per-language leaderboard (win rate + move distribution + bias).
#
# Prerequisites (see README "Language tournament"):
#   - The arena is running:  ./run-server.sh vt   (start it with a small
#     ARENA_TOTAL_ROUNDS, e.g. ARENA_TOTAL_ROUNDS=200, for a fast run)
#   - Go client built:       (cd clients/go && ./generate_protos.sh && go build -o streaming_client streaming_client.go)
#   - Python:                grpcio installed and clients/python stubs generated
#   - Java client:           mutiny-server built (./gradlew :mutiny-server:quarkusBuild)
#
# Usage: tournament/run-tournament.sh [--host H] [--port P] [--clients N] [--matches M]
#   N clients per language, each playing M matches  ->  ~ (3*N/2)*M total matches.
set -uo pipefail

HOST=localhost; PORT=9000; CLIENTS=4; MATCHES=1000; CLIENT_TIMEOUT=600
while [ $# -gt 0 ]; do
  case "$1" in
    --host) HOST="$2"; shift 2;;
    --port) PORT="$2"; shift 2;;
    --clients) CLIENTS="$2"; shift 2;;
    --matches) MATCHES="$2"; shift 2;;
    --client-timeout) CLIENT_TIMEOUT="$2"; shift 2;;
    *) echo "unknown arg: $1"; exit 1;;
  esac
done

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
QA="$ROOT/mutiny-server/build/quarkus-app"
JCP="$QA/app/*:$QA/lib/main/*:$QA/lib/boot/*"

total=$(( (3 * CLIENTS * MATCHES) / 2 ))
echo "Tournament: ${CLIENTS} clients/language x ${MATCHES} matches  (~${total} matches) on ${HOST}:${PORT}"
echo

# Each client is wrapped in `timeout`: languages finish at different speeds, so the
# last client can be left without an opponent and would otherwise wait forever.
# When it's killed, the arena results are still valid (aggregated from the matches
# that DID complete). Raise --client-timeout for very large runs.
pids=()
for i in $(seq 1 "$CLIENTS"); do
  ( cd "$ROOT/clients/python" && timeout "$CLIENT_TIMEOUT" python3 streaming_client.py \
      --host "$HOST" --port "$PORT" --language Python --prng random.Random --matches "$MATCHES" ) & pids+=($!)
  timeout "$CLIENT_TIMEOUT" "$ROOT/clients/go/streaming_client" -host "$HOST" -port "$PORT" \
      -language Go -prng math/rand -matches "$MATCHES" & pids+=($!)
  timeout "$CLIENT_TIMEOUT" java -cp "$JCP" -Darena.host="$HOST" -Darena.port="$PORT" \
      -Dlanguage.name=Java -Dprng.algorithm=java.util.Random -Dmatches="$MATCHES" \
      ai.pipestream.client.v1.StreamingClient & pids+=($!)
done

for p in "${pids[@]}"; do wait "$p" || true; done

echo
echo "All clients finished. Fetching arena results..."
python3 "$ROOT/tournament/results.py" --host "$HOST" --port "$PORT"
