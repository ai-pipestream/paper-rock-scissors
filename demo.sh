#!/bin/bash
# Demo script — run a complete unary + streaming match against a running arena.
#
# Usage: ./demo.sh [port]
#   port 8080 (default) — Quarkus servers (./run-server.sh vt)
#   port 9000           — vanilla netty-server (./run-server.sh netty)

set -e

PORT="${1:-${ARENA_PORT:-8080}}"

echo "======================================"
echo "Paper-Rock-Scissors Arena Demo"
echo "======================================"
echo ""
echo "Target: localhost:${PORT}"
echo "This script demonstrates both Unary and Streaming approaches"
echo ""

if ! nc -z localhost "$PORT" 2>/dev/null; then
    echo "ERROR: Server is not running on port ${PORT}"
    echo "Start one first:"
    echo "  ./run-server.sh vt      # Quarkus, port 8080"
    echo "  ./run-server.sh netty   # vanilla gRPC, port 9000  ->  ./demo.sh 9000"
    exit 1
fi

echo "Server detected on port ${PORT}"
echo ""

# Build the Java client classpath once up front so the parallel client
# launches below don't both kick off a Gradle build.
if [ ! -d "mutiny-server/build/quarkus-app/app" ]; then
    echo "Building Java client classpath (one-time) ..."
    ./gradlew :mutiny-server:quarkusBuild -q
    echo ""
fi

# The vanilla netty-server implements only the STREAMING service.
if [ "$PORT" = "9000" ]; then
    echo "=== Demo 1: Unary (Polling) — SKIPPED ==="
    echo "netty-server (port 9000) only implements the streaming service."
    echo "Run a Quarkus server (./run-server.sh vt) for the unary demo."
    echo ""
else
    echo "=== Demo 1: Unary (Polling) Approach ==="
    echo "Starting two Unary clients..."
    echo ""

    ./run-unary-client.sh "Java-Unary-1" "java.util.Random" "$PORT" &
    CLIENT1_PID=$!
    sleep 2

    ./run-unary-client.sh "Java-Unary-2" "java.security.SecureRandom" "$PORT" &
    CLIENT2_PID=$!

    wait $CLIENT1_PID
    wait $CLIENT2_PID

    echo ""
    echo "Unary match completed!"
    echo ""
    sleep 3
fi

echo "=== Demo 2: Streaming (Push) Approach ==="
echo "Starting two Streaming clients..."
echo ""

./run-streaming-client.sh "Java-Streaming-1" "L64X128MixRandom" "$PORT" &
CLIENT3_PID=$!
sleep 2

./run-streaming-client.sh "Java-Streaming-2" "SplittableRandom" "$PORT" &
CLIENT4_PID=$!

wait $CLIENT3_PID
wait $CLIENT4_PID

echo ""
echo "Streaming match completed!"
echo ""
echo "======================================"
echo "Demo Complete!"
echo "======================================"
echo ""
echo "Next: docs/lessons/README.md"
