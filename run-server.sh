#!/bin/bash
# Start the Paper-Rock-Scissors Arena Server

echo "Starting Quarkus Arena Server..."
echo "gRPC server will be available on port 9000"
echo "HTTP health checks on port 8080"
echo ""

mvn quarkus:dev
