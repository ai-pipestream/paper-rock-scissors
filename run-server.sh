#!/bin/bash
# Start a Paper-Rock-Scissors Arena server.
#
# Usage: ./run-server.sh [mutiny|vt]
#   mutiny (default) -> :mutiny-server  (reactive: Mutiny + Hibernate Reactive)
#   vt               -> :vt-server      (virtual threads + blocking Hibernate ORM)
#
# Both serve the SAME gRPC contracts. Quarkus Dev Services starts PostgreSQL
# automatically. gRPC + health are exposed on HTTP port 8080.
set -e

VARIANT="${1:-mutiny}"
case "$VARIANT" in
  mutiny)                     MODULE="mutiny-server" ;;
  vt|virtual|virtual-threads) MODULE="vt-server" ;;
  *) echo "Unknown variant: '$VARIANT' (use 'mutiny' or 'vt')"; exit 1 ;;
esac

echo "Starting the '$VARIANT' arena (:$MODULE) ..."
echo "gRPC + health on HTTP port 8080. PostgreSQL via Dev Services."
echo ""
exec ./gradlew ":${MODULE}:quarkusDev"
