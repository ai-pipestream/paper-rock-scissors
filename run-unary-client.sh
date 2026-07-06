#!/bin/bash
# Run the Java Unary client (register + submit + poll — the painful way).
#
# Usage: ./run-unary-client.sh [name] [prng] [port]
#   port 8080 (default) — Quarkus servers (./run-server.sh vt|mutiny)
#   NOTE: the vanilla netty-server (port 9000) implements only the STREAMING
#   service, so unary clients need one of the Quarkus servers.
#
# The client runs off the built mutiny-server classpath (same mechanism as
# tournament/run-tournament.sh); it is built automatically on first use.
set -e

CLIENT_NAME="${1:-Java-21-Unary}"
PRNG_ALGO="${2:-java.util.Random}"
ARENA_PORT="${3:-${ARENA_PORT:-8080}}"

ROOT="$(cd "$(dirname "$0")" && pwd)"
QA="$ROOT/mutiny-server/build/quarkus-app"

if [ ! -d "$QA/app" ]; then
    echo "Client classpath not built yet — running ./gradlew :mutiny-server:quarkusBuild ..."
    "$ROOT/gradlew" :mutiny-server:quarkusBuild -q
fi

echo "Starting Unary Client: $CLIENT_NAME ($PRNG_ALGO) -> localhost:${ARENA_PORT}"
echo "This client will poll the server for results (the painful way)"
echo ""

exec java -cp "$QA/app/*:$QA/lib/main/*:$QA/lib/boot/*" \
  -Darena.host=localhost \
  -Darena.port="$ARENA_PORT" \
  -Dlanguage.name="$CLIENT_NAME" \
  -Dprng.algorithm="$PRNG_ALGO" \
  ai.pipestream.client.v1.UnaryClient
