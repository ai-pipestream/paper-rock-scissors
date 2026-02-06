# Go Clients for Paper-Rock-Scissors Arena

Go implementation of the "Gladiator" clients for the Paper-Rock-Scissors gRPC tournament system.

## Requirements

- Go 1.21+
- Protocol Buffers compiler (protoc)

## Installation

```bash
# Install dependencies
go mod download

# Install protoc-gen-go tools (if not already installed)
go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest
```

## Generate gRPC Stubs

If the proto files change, regenerate the Go stubs:

```bash
./generate_protos.sh
```

## Building the Clients

```bash
# Build unary client
go build -o unary_client unary_client.go

# Build streaming client
go build -o streaming_client streaming_client.go

# Or build both
go build -o unary_client unary_client.go && go build -o streaming_client streaming_client.go
```

## Running the Clients

### Unary Client (Polling Approach)

```bash
# Basic usage
./unary_client

# With custom parameters
./unary_client -host localhost -port 9000 -language Go-1.21 -prng math/rand
```

### Streaming Client (Push Approach)

```bash
# Basic usage
./streaming_client

# With custom parameters
./streaming_client -host localhost -port 9000 -language Go-1.21 -prng crypto/rand
```

## Client Options

- `-host`: Arena server hostname (default: localhost)
- `-port`: Arena server port (default: 9000)
- `-language`: Language identifier for statistics (default: Go-1.21)
- `-prng`: PRNG algorithm name for statistics (default: math/rand)

## PRNG Algorithms

Go clients can use different random number generators:
- `math/rand` - Standard pseudo-random number generator
- `crypto/rand` - Cryptographically secure random number generator

## Architecture

Both clients follow the "Dumb Client" pattern - they only calculate `rand() % 3` when requested by the Judge (server).

### Unary Client Flow
1. Register with the arena
2. Submit a move for each round
3. **Poll** for the result (the painful part)
4. Repeat for 1,000 rounds

### Streaming Client Flow
1. Open bidirectional stream and send handshake
2. Wait for server to request a move (the "pulse")
3. Send move immediately
4. Receive result immediately
5. Repeat for 1,000 rounds

## Notes

- The streaming client uses Go's native goroutine-safe gRPC bidirectional streaming
- Both clients will wait up to 2 seconds for an opponent to join
- Round results are logged every 100 rounds
- The binaries are statically compiled and can be run on any Linux system
