#!/bin/bash
# Generate Python gRPC stubs from proto files

PROTO_DIR="../../src/main/proto"
OUT_DIR="."

python3 -m grpc_tools.protoc \
    -I${PROTO_DIR} \
    --python_out=${OUT_DIR} \
    --grpc_python_out=${OUT_DIR} \
    ${PROTO_DIR}/tourney_unary.proto \
    ${PROTO_DIR}/tourney_stream.proto

echo "Python stubs generated successfully!"
