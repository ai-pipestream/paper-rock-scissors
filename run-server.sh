#!/bin/bash
# Start a Paper-Rock-Scissors Arena server.
#
# Usage: ./run-server.sh [mutiny|vt|netty]
#   mutiny (default) -> :mutiny-server  (reactive: Mutiny + Hibernate Reactive)
#   vt               -> :vt-server      (virtual threads + blocking Hibernate ORM)
#   netty            -> :netty-server   (vanilla grpc-java / Netty, NO Quarkus)
#
# All three serve the SAME gRPC contracts. The two Quarkus builds expose gRPC on
# HTTP port 8080 with PostgreSQL via Dev Services; the vanilla netty build listens
# on a dedicated gRPC port 9000 with an in-memory leaderboard (no database).
set -e

VARIANT="${1:-mutiny}"
case "$VARIANT" in
  mutiny)
    echo "Starting the 'mutiny' arena (:mutiny-server) ..."
    echo "gRPC + health on HTTP port 8080. PostgreSQL via Dev Services."
    echo ""
    exec ./gradlew ":mutiny-server:quarkusDev" ;;
  vt|virtual|virtual-threads)
    echo "Starting the 'vt' arena (:vt-server) ..."
    echo "gRPC + health on HTTP port 8080. PostgreSQL via Dev Services."
    echo ""
    exec ./gradlew ":vt-server:quarkusDev" ;;
  netty|vanilla|grpc)
    # The control group: plain grpc-java, no framework. Tunables are env vars,
    # e.g. ARENA_PORT, ARENA_TOTAL_ROUNDS, ARENA_FLOW_CONTROL_WINDOW (bytes),
    # ARENA_MAX_CONCURRENT_STREAMS, ARENA_VIRTUAL_THREADS=true.
    echo "Starting the 'netty' arena (:netty-server, vanilla grpc-java) ..."
    echo "gRPC on port ${ARENA_PORT:-9000}. In-memory leaderboard (no database)."
    echo ""
    ./gradlew ":netty-server:installDist" -q
    exec netty-server/build/install/netty-server/bin/netty-server ;;
  *)
    echo "Unknown variant: '$VARIANT' (use 'mutiny', 'vt', or 'netty')"; exit 1 ;;
esac
