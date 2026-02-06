#!/usr/bin/env python3
"""
Python Streaming client implementation demonstrating the "clean" approach.
"""

import sys
import random
import argparse
import grpc

import tourney_stream_pb2
import tourney_stream_pb2_grpc


class StreamingClient:
    def __init__(self, host='localhost', port=9000, language_name='Python-3.12', prng_algorithm='random.Random'):
        self.channel = grpc.insecure_channel(f'{host}:{port}')
        self.stub = tourney_stream_pb2_grpc.StreamingArenaStub(self.channel)
        self.language_name = language_name
        self.prng_algorithm = prng_algorithm
        self.random = random.Random()
        self.rounds_completed = 0
    
    def close(self):
        self.channel.close()
    
    def play(self):
        """Interactive bidirectional streaming implementation"""
        print(f"Streaming Client starting: {self.language_name} ({self.prng_algorithm})")
        
        import queue
        import threading
        
        request_queue = queue.Queue()
        
        def request_iterator():
            """Generator that yields messages from the queue"""
            while True:
                msg = request_queue.get()
                if msg is None:  # Sentinel to stop
                    break
                yield msg
        
        # Start the bidirectional stream
        responses = self.stub.Battle(request_iterator())
        
        # Send handshake first
        request_queue.put(tourney_stream_pb2.ArenaMessage(
            handshake=tourney_stream_pb2.Handshake(
                language_name=self.language_name,
                prng_algorithm=self.prng_algorithm
            )
        ))
        
        # Process server responses
        try:
            for update in responses:
                if update.HasField('status'):
                    status = update.status
                    print(f"Status: {status}")
                    
                    if status in ["MATCH_COMPLETE", "OPPONENT_DISCONNECTED"]:
                        request_queue.put(None)  # Signal to stop request generator
                        break
                        
                elif update.HasField('trigger'):
                    # Server requesting a move - respond immediately
                    move = self.random.randint(0, 2)
                    request_queue.put(tourney_stream_pb2.ArenaMessage(
                        move=tourney_stream_pb2.Move(move=move)
                    ))
                    
                elif update.HasField('result'):
                    # Round result received
                    self.rounds_completed += 1
                    result = update.result
                    if self.rounds_completed % 100 == 0:
                        print(f"Round {result.round_id}: {result.outcome}")
            
            print(f"Match completed! Total rounds: {self.rounds_completed}")
        except grpc.RpcError as e:
            if e.code() != grpc.StatusCode.CANCELLED:
                print(f"Stream error: {e}")
        finally:
            request_queue.put(None)  # Ensure generator stops


def main():
    parser = argparse.ArgumentParser(description='Python Streaming Client for Paper-Rock-Scissors Arena')
    parser.add_argument('--host', default='localhost', help='Arena server host')
    parser.add_argument('--port', type=int, default=9000, help='Arena server port')
    parser.add_argument('--language', default='Python-3.12', help='Language name')
    parser.add_argument('--prng', default='random.Random', help='PRNG algorithm')
    
    args = parser.parse_args()
    
    client = StreamingClient(
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
