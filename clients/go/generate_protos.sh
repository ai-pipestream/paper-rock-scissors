#!/bin/bash
# Generate Go gRPC stubs from proto files

PROTO_DIR="../../src/main/proto"

# Generate for unary
protoc \
    -I${PROTO_DIR} \
    --go_out=. \
    --go_opt=module=github.com/ai-pipestream/paper-rock-scissors/clients/go \
    --go-grpc_out=. \
    --go-grpc_opt=module=github.com/ai-pipestream/paper-rock-scissors/clients/go \
    ${PROTO_DIR}/ai/pipestream/tourney/unary/v1/unary.proto

# Generate for stream
protoc \
    -I${PROTO_DIR} \
    --go_out=. \
    --go_opt=module=github.com/ai-pipestream/paper-rock-scissors/clients/go \
    --go-grpc_out=. \
    --go-grpc_opt=module=github.com/ai-pipestream/paper-rock-scissors/clients/go \
    ${PROTO_DIR}/ai/pipestream/tourney/stream/v1/stream.proto

echo "Go stubs generated successfully!"