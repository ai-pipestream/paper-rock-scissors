#!/usr/bin/env python3
"""
Fetch and pretty-print the arena leaderboard (StreamingArenaService.GetArenaResults).

Reuses the Python client's generated stubs. Run after a tournament to see, per
language: matches played, round win-rate, move distribution, and how far the most
frequent move sits from a uniform 33.3% (the PRNG-bias signal).
"""

import argparse
import os
import sys

import grpc

# The Python client generates its stubs flat in clients/python.
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "clients", "python"))
try:
    import stream_pb2
    import stream_pb2_grpc
except ImportError:
    from ai.pipestream.tourney.stream.v1 import stream_pb2, stream_pb2_grpc


def main():
    ap = argparse.ArgumentParser(description="Print the arena leaderboard")
    ap.add_argument("--host", default="localhost")
    ap.add_argument("--port", type=int, default=9000)
    args = ap.parse_args()

    channel = grpc.insecure_channel(f"{args.host}:{args.port}")
    stub = stream_pb2_grpc.StreamingArenaServiceStub(channel)
    resp = stub.GetArenaResults(stream_pb2.ArenaResultsRequest())

    print()
    print(f"ARENA RESULTS — {resp.total_matches:,} streaming matches")
    print("=" * 92)
    print(f"{'language':<16}{'matches':>9}{'win%':>8}{'rock%':>8}{'paper%':>8}{'scissors%':>10}"
          f"{'bias%':>8}{'moves':>13}")
    print("-" * 92)
    for r in resp.languages:
        moves = r.rocks + r.papers + r.scissors
        print(f"{r.language:<16}{r.matches_played:>9,}{r.win_rate * 100:>7.2f}%"
              f"{r.rock_pct:>7.2f}%{r.paper_pct:>7.2f}%{r.scissors_pct:>9.2f}%"
              f"{r.move_bias_pct:>+7.2f}%{moves:>13,}")
    print("=" * 92)
    print("win% ~50 is expected vs a random opponent; the story is in rock/paper/scissors %")
    print("and bias% (most-frequent move minus 33.33). Bigger |bias| = less-uniform PRNG.")
    print()


if __name__ == "__main__":
    main()
