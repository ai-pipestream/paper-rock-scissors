#!/bin/bash
# Run two Unary clients to demonstrate the polling approach

CLIENT_NAME="${1:-Java-21-Unary}"
PRNG_ALGO="${2:-java.util.Random}"

echo "Starting Unary Client: $CLIENT_NAME ($PRNG_ALGO)"
echo "This client will poll the server for results (the painful way)"
echo ""

mvn exec:java -Dexec.mainClass="com.rickert.client.UnaryClient" \
  -Dlanguage.name="$CLIENT_NAME" \
  -Dprng.algorithm="$PRNG_ALGO" \
  -Darena.host="localhost" \
  -Darena.port="9000"
