# Python Clients for Paper-Rock-Scissors Arena

Python implementation of the "Gladiator" clients for the Paper-Rock-Scissors gRPC tournament system.

## Requirements

- Python 3.8+
- gRPC libraries

## Installation

```bash
pip install -r requirements.txt
```

## Generate gRPC Stubs

If the proto files change, regenerate the Python stubs:

```bash
./generate_protos.sh
```

## Running the Clients

### Unary Client (Polling Approach)

```bash
# Basic usage
python3 unary_client.py

# With custom parameters
python3 unary_client.py --host localhost --port 9000 --language Python-3.12 --prng random.Random
```

### Streaming Client (Push Approach)

```bash
# Basic usage
python3 streaming_client.py

# With custom parameters
python3 streaming_client.py --host localhost --port 9000 --language Python-3.12 --prng random.SystemRandom
```

## Client Options

- `--host`: Arena server hostname (default: localhost)
- `--port`: Arena server port (default: 9000)
- `--language`: Language identifier for statistics (default: Python-3.12)
- `--prng`: PRNG algorithm name for statistics (default: random.Random)

## PRNG Algorithms

Python clients can use different random number generators:
- `random.Random` - Standard Mersenne Twister PRNG
- `random.SystemRandom` - OS-provided randomness (crypto-grade)

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

- The streaming client uses a queue-based approach to handle bidirectional streaming in Python
- Both clients will wait up to 2 seconds for an opponent to join
- Round results are logged every 100 rounds
