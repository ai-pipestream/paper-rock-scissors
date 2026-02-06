#!/bin/bash
# Run a Streaming client to demonstrate the clean push approach

CLIENT_NAME="${1:-Java-21-Streaming}"
PRNG_ALGO="${2:-L64X128MixRandom}"

echo "Starting Streaming Client: $CLIENT_NAME ($PRNG_ALGO)"
echo "This client uses bidirectional streaming (the clean way)"
echo ""

./gradlew run -Dquarkus.args="--streaming" \
  -Dlanguage.name="$CLIENT_NAME" \
  -Dprng.algorithm="$PRNG_ALGO" \
  -Darena.host="localhost" \
  -Darena.port="9000"