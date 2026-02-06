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
    ${PROTO_DIR}/tourney_unary.proto

# Generate for stream
protoc \
    -I${PROTO_DIR} \
    --go_out=. \
    --go_opt=module=github.com/ai-pipestream/paper-rock-scissors/clients/go \
    --go-grpc_out=. \
    --go-grpc_opt=module=github.com/ai-pipestream/paper-rock-scissors/clients/go \
    ${PROTO_DIR}/tourney_stream.proto

echo "Go stubs generated successfully!"
