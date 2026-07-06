#!/bin/bash
# Run the Java Streaming client (bidirectional push — the clean way).
#
# Usage: ./run-streaming-client.sh [name] [prng] [port]
#   port 8080 (default) — Quarkus servers (./run-server.sh vt|mutiny)
#   port 9000           — vanilla netty-server (./run-server.sh netty)
#
# The client runs off the built mutiny-server classpath (same mechanism as
# tournament/run-tournament.sh); it is built automatically on first use.
set -e

CLIENT_NAME="${1:-Java-21-Streaming}"
PRNG_ALGO="${2:-L64X128MixRandom}"
ARENA_PORT="${3:-${ARENA_PORT:-8080}}"

ROOT="$(cd "$(dirname "$0")" && pwd)"
QA="$ROOT/mutiny-server/build/quarkus-app"

if [ ! -d "$QA/app" ]; then
    echo "Client classpath not built yet — running ./gradlew :mutiny-server:quarkusBuild ..."
    "$ROOT/gradlew" :mutiny-server:quarkusBuild -q
fi

echo "Starting Streaming Client: $CLIENT_NAME ($PRNG_ALGO) -> localhost:${ARENA_PORT}"
echo "This client uses bidirectional streaming (the clean way)"
echo ""

exec java -cp "$QA/app/*:$QA/lib/main/*:$QA/lib/boot/*" \
  -Darena.host=localhost \
  -Darena.port="$ARENA_PORT" \
  -Dlanguage.name="$CLIENT_NAME" \
  -Dprng.algorithm="$PRNG_ALGO" \
  ai.pipestream.client.v1.StreamingClient
