#!/bin/bash
# Run a Unary client to demonstrate the polling approach
#
# Usage: ./run-unary-client.sh [name] [prng] [port]
# Default port 8080 matches Quarkus (./run-server.sh vt|mutiny).
# Use port 9000 for vanilla netty-server (./run-server.sh netty).

CLIENT_NAME="${1:-Java-21-Unary}"
PRNG_ALGO="${2:-java.util.Random}"
ARENA_PORT="${3:-${ARENA_PORT:-8080}}"

echo "Starting Unary Client: $CLIENT_NAME ($PRNG_ALGO) -> localhost:${ARENA_PORT}"
echo "This client will poll the server for results (the painful way)"
echo ""

./gradlew run -Dquarkus.args="--unary" \
  -Dlanguage.name="$CLIENT_NAME" \
  -Dprng.algorithm="$PRNG_ALGO" \
  -Darena.host="localhost" \
  -Darena.port="$ARENA_PORT"
