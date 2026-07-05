#!/usr/bin/env python3
"""
Python Streaming client — the "clean" bidirectional approach.

Plays one or many matches (--matches). Each match opens a fresh Battle stream,
handshakes with the language + PRNG label, and answers every "pulse" with a move
drawn from Python's default PRNG (random.Random / Mersenne Twister). Used by the
language tournament to sample this PRNG's move distribution.
"""

import argparse
import queue
import random

import grpc

try:
    from ai.pipestream.tourney.stream.v1 import stream_pb2
    from ai.pipestream.tourney.stream.v1 import stream_pb2_grpc
except ImportError:
    # Fallback for flat structure
    import stream_pb2
    import stream_pb2_grpc


class StreamingClient:
    def __init__(self, host='localhost', port=9000, language_name='Python-3.12',
                 prng_algorithm='random.Random', verbose=False):
        self.channel = grpc.insecure_channel(f'{host}:{port}')
        self.stub = stream_pb2_grpc.StreamingArenaServiceStub(self.channel)
        self.language_name = language_name
        self.prng_algorithm = prng_algorithm
        self.random = random.Random()
        self.verbose = verbose

    def close(self):
        self.channel.close()

    def play_match(self):
        """Play a single match to completion. Returns rounds played."""
        rounds = 0
        request_queue = queue.Queue()

        def request_iterator():
            while True:
                msg = request_queue.get()
                if msg is None:  # sentinel
                    break
                yield msg

        responses = self.stub.Battle(request_iterator())
        request_queue.put(stream_pb2.BattleRequest(
            handshake=stream_pb2.Handshake(
                language_name=self.language_name,
                prng_algorithm=self.prng_algorithm,
            )
        ))

        try:
            for update in responses:
                if update.HasField('status'):
                    if self.verbose:
                        print(f"Status: {update.status}")
                    if update.status in ("MATCH_COMPLETE", "OPPONENT_DISCONNECTED"):
                        request_queue.put(None)
                        break
                elif update.HasField('trigger'):
                    move = self.random.randint(0, 2)  # 0=Rock 1=Paper 2=Scissors
                    request_queue.put(stream_pb2.BattleRequest(move=stream_pb2.Move(move=move)))
                elif update.HasField('result'):
                    rounds += 1
        except grpc.RpcError as e:
            if e.code() != grpc.StatusCode.CANCELLED:
                print(f"Stream error: {e}")
        finally:
            request_queue.put(None)
        return rounds


def main():
    parser = argparse.ArgumentParser(description='Python Streaming Client for Paper-Rock-Scissors Arena')
    parser.add_argument('--host', default='localhost', help='Arena server host')
    parser.add_argument('--port', type=int, default=9000, help='Arena server port')
    parser.add_argument('--language', default='Python-3.12', help='Language name')
    parser.add_argument('--prng', default='random.Random', help='PRNG algorithm')
    parser.add_argument('--matches', type=int, default=1, help='Number of matches to play')
    parser.add_argument('--verbose', action='store_true', help='Print per-round/status detail')
    args = parser.parse_args()

    client = StreamingClient(
        host=args.host, port=args.port,
        language_name=args.language, prng_algorithm=args.prng,
        verbose=args.verbose,
    )

    print(f"{args.language} ({args.prng}): playing {args.matches} match(es)...")
    total_rounds = 0
    try:
        for i in range(args.matches):
            total_rounds += client.play_match()
            if args.matches > 1 and (i + 1) % max(1, args.matches // 10) == 0:
                print(f"  {args.language}: {i + 1}/{args.matches} matches")
    finally:
        client.close()
    print(f"{args.language} done: {args.matches} matches, {total_rounds} rounds")


if __name__ == '__main__':
    main()
