#!/bin/bash
# Run a Streaming client to demonstrate the clean push approach
#
# Usage: ./run-streaming-client.sh [name] [prng] [port]
# Default port 8080 matches Quarkus (./run-server.sh vt|mutiny).
# Use port 9000 for vanilla netty-server (./run-server.sh netty).

CLIENT_NAME="${1:-Java-21-Streaming}"
PRNG_ALGO="${2:-L64X128MixRandom}"
ARENA_PORT="${3:-${ARENA_PORT:-8080}}"

echo "Starting Streaming Client: $CLIENT_NAME ($PRNG_ALGO) -> localhost:${ARENA_PORT}"
echo "This client uses bidirectional streaming (the clean way)"
echo ""

./gradlew run -Dquarkus.args="--streaming" \
  -Dlanguage.name="$CLIENT_NAME" \
  -Dprng.algorithm="$PRNG_ALGO" \
  -Darena.host="localhost" \
  -Darena.port="$ARENA_PORT"
