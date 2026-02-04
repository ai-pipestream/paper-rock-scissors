#!/bin/bash
# Demo script to run a complete match

echo "======================================"
echo "Paper-Rock-Scissors Arena Demo"
echo "======================================"
echo ""
echo "This script will demonstrate both Unary and Streaming approaches"
echo ""

# Check if server is running
if ! nc -z localhost 9000 2>/dev/null; then
    echo "ERROR: Server is not running on port 9000"
    echo "Please start the server first with: ./run-server.sh"
    exit 1
fi

echo "Server detected on port 9000"
echo ""

# Demo 1: Unary clients
echo "=== Demo 1: Unary (Polling) Approach ==="
echo "Starting two Unary clients..."
echo ""

# Run clients in background
./run-unary-client.sh "Java-Unary-1" "java.util.Random" &
CLIENT1_PID=$!
sleep 2

./run-unary-client.sh "Java-Unary-2" "java.security.SecureRandom" &
CLIENT2_PID=$!

# Wait for clients
wait $CLIENT1_PID
wait $CLIENT2_PID

echo ""
echo "Unary match completed!"
echo ""
sleep 3

# Demo 2: Streaming clients
echo "=== Demo 2: Streaming (Push) Approach ==="
echo "Starting two Streaming clients..."
echo ""

# Run clients in background
./run-streaming-client.sh "Java-Streaming-1" "L64X128MixRandom" &
CLIENT3_PID=$!
sleep 2

./run-streaming-client.sh "Java-Streaming-2" "SplittableRandom" &
CLIENT4_PID=$!

# Wait for clients
wait $CLIENT3_PID
wait $CLIENT4_PID

echo ""
echo "Streaming match completed!"
echo ""
echo "======================================"
echo "Demo Complete!"
echo "======================================"
echo ""
echo "Check the server logs for statistics and performance comparison"
