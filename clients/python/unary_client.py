#!/usr/bin/env python3
"""
Python Unary client implementation demonstrating the "painful" polling approach.
"""

import sys
import time
import random
import argparse
import grpc

try:
    from ai.pipestream.tourney.unary.v1 import unary_pb2
    from ai.pipestream.tourney.unary.v1 import unary_pb2_grpc
except ImportError:
    # Fallback for flat structure
    import unary_pb2
    import unary_pb2_grpc


class UnaryClient:
    def __init__(self, host='localhost', port=9000, language_name='Python-3.12', prng_algorithm='random.Random'):
        self.channel = grpc.insecure_channel(f'{host}:{port}')
        self.stub = unary_pb2_grpc.UnaryArenaServiceStub(self.channel)
        self.language_name = language_name
        self.prng_algorithm = prng_algorithm
        self.random = random.Random()
    
    def close(self):
        self.channel.close()
    
    def play(self):
        print(f"Unary Client starting: {self.language_name} ({self.prng_algorithm})")
        
        # Step 1: Register
        reg_response = self.stub.Register(
            unary_pb2.RegisterRequest(
                language_name=self.language_name,
                prng_algorithm=self.prng_algorithm
            )
        )
        
        match_id = reg_response.match_id
        print(f"Registered with matchId: {match_id}, Status: {reg_response.status}")
        
        # Wait for opponent if needed
        if reg_response.status == "WAITING_FOR_OPPONENT":
            print("Waiting for opponent...")
            time.sleep(2)
        
        print("Starting match...")
        
        # Play rounds
        for round_num in range(1, 1001):
            # Step 2: Submit move
            move = self.random.randint(0, 2)  # 0=Rock, 1=Paper, 2=Scissors
            
            move_response = self.stub.SubmitMove(
                unary_pb2.SubmitMoveRequest(
                    match_id=match_id,
                    round_number=round_num,
                    move=move
                )
            )
            
            if move_response.status == "GAME_OVER":
                print("Game is over")
                break
            
            if move_response.status != "ACCEPTED":
                print(f"Move not accepted: {move_response.status}")
                continue
            
            # Step 3: Poll for result (THE PAINFUL PART)
            result = None
            poll_attempts = 0
            while result is None or result.status == "PENDING":
                poll_attempts += 1
                result = self.stub.CheckRoundResult(
                    unary_pb2.CheckRoundResultRequest(
                        match_id=match_id,
                        round_number=round_num
                    )
                )
                
                if result.status == "PENDING":
                    time.sleep(0.01)  # Polling delay
            
            if round_num % 100 == 0:
                print(f"Round {round_num}: {result.outcome} (Polls: {poll_attempts})")
        
        print("Match completed!")


def main():
    parser = argparse.ArgumentParser(description='Python Unary Client for Paper-Rock-Scissors Arena')
    parser.add_argument('--host', default='localhost', help='Arena server host')
    parser.add_argument('--port', type=int, default=9000, help='Arena server port')
    parser.add_argument('--language', default='Python-3.12', help='Language name')
    parser.add_argument('--prng', default='random.Random', help='PRNG algorithm')
    
    args = parser.parse_args()
    
    client = UnaryClient(
        host=args.host,
        port=args.port,
        language_name=args.language,
        prng_algorithm=args.prng
    )
    
    try:
        client.play()
    finally:
        client.close()


if __name__ == '__main__':
    main()