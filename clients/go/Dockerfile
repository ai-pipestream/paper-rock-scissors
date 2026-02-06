####
# Multi-stage Docker build for Go clients
#
# Build the image with:
# docker build -f clients/go/Dockerfile -t paper-rock-scissors-go-client .
#
# Run the unary client:
# docker run --rm --network host paper-rock-scissors-go-client ./unary_client
#
# Run the streaming client:
# docker run --rm --network host paper-rock-scissors-go-client ./streaming_client
###

# Build stage
FROM golang:1.22-alpine AS builder

# Install build dependencies
RUN apk add --no-cache git protobuf protobuf-dev

WORKDIR /build

# Copy go mod files
COPY clients/go/go.mod clients/go/go.sum ./

# Download dependencies
RUN go mod download

# Install protoc-gen-go tools
RUN go install google.golang.org/protobuf/cmd/protoc-gen-go@latest && \
    go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest

# Copy proto files and generate stubs
COPY src/main/proto/ /build/proto/
COPY clients/go/generate_protos.sh .
RUN chmod +x generate_protos.sh && \
    sed -i 's|../../src/main/proto|/build/proto|g' generate_protos.sh && \
    export PATH=$PATH:$(go env GOPATH)/bin && \
    ./generate_protos.sh

# Copy source code
COPY clients/go/*.go ./

# Build the binaries
RUN CGO_ENABLED=0 GOOS=linux go build -a -installsuffix cgo -o unary_client unary_client.go && \
    CGO_ENABLED=0 GOOS=linux go build -a -installsuffix cgo -o streaming_client streaming_client.go

# Runtime stage
FROM alpine:latest

RUN apk --no-cache add ca-certificates

WORKDIR /app

# Copy binaries from builder
COPY --from=builder /build/unary_client .
COPY --from=builder /build/streaming_client .

# Make binaries executable
RUN chmod +x unary_client streaming_client

CMD ["./unary_client", "-help"]
