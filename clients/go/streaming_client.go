package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"log"
	"math/rand"
	"time"

	pb "github.com/ai-pipestream/paper-rock-scissors/clients/go/pb/ai/pipestream/tourney/stream/v1"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

// StreamingClient implements the "clean" streaming approach. Answers every "pulse"
// with a move from Go's default PRNG (math/rand), for the language tournament.
type StreamingClient struct {
	conn         *grpc.ClientConn
	client       pb.StreamingArenaServiceClient
	languageName string
	prngAlgo     string
	random       *rand.Rand
	verbose      bool
}

func NewStreamingClient(host string, port int, languageName, prngAlgo string, verbose bool) (*StreamingClient, error) {
	address := fmt.Sprintf("%s:%d", host, port)
	conn, err := grpc.Dial(address, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		return nil, fmt.Errorf("failed to connect: %v", err)
	}
	return &StreamingClient{
		conn:         conn,
		client:       pb.NewStreamingArenaServiceClient(conn),
		languageName: languageName,
		prngAlgo:     prngAlgo,
		random:       rand.New(rand.NewSource(time.Now().UnixNano())),
		verbose:      verbose,
	}, nil
}

func (c *StreamingClient) Close() {
	if c.conn != nil {
		c.conn.Close()
	}
}

// PlayMatch plays a single match to completion and returns the rounds played.
func (c *StreamingClient) PlayMatch() (int, error) {
	rounds := 0
	stream, err := c.client.Battle(context.Background())
	if err != nil {
		return 0, fmt.Errorf("failed to create stream: %v", err)
	}

	if err := stream.Send(&pb.BattleRequest{
		Payload: &pb.BattleRequest_Handshake{
			Handshake: &pb.Handshake{LanguageName: c.languageName, PrngAlgorithm: c.prngAlgo},
		},
	}); err != nil {
		return rounds, fmt.Errorf("failed to send handshake: %v", err)
	}

	for {
		update, err := stream.Recv()
		if err == io.EOF {
			break
		}
		if err != nil {
			return rounds, fmt.Errorf("receive error: %v", err)
		}

		switch payload := update.Payload.(type) {
		case *pb.BattleResponse_Status:
			if c.verbose {
				log.Printf("Status: %s", payload.Status)
			}
			if payload.Status == "MATCH_COMPLETE" || payload.Status == "OPPONENT_DISCONNECTED" {
				stream.CloseSend()
				return rounds, nil
			}
		case *pb.BattleResponse_Trigger:
			move := int32(c.random.Intn(3)) // 0=Rock, 1=Paper, 2=Scissors
			if err := stream.Send(&pb.BattleRequest{
				Payload: &pb.BattleRequest_Move{Move: &pb.Move{Move: move}},
			}); err != nil {
				return rounds, fmt.Errorf("failed to send move: %v", err)
			}
		case *pb.BattleResponse_Result:
			rounds++
		}
	}
	return rounds, nil
}

func main() {
	host := flag.String("host", "localhost", "Arena server host")
	port := flag.Int("port", 9000, "Arena server port")
	language := flag.String("language", "Go-1.21", "Language name")
	prng := flag.String("prng", "math/rand", "PRNG algorithm")
	matches := flag.Int("matches", 1, "Number of matches to play")
	verbose := flag.Bool("verbose", false, "Print per-round/status detail")
	flag.Parse()

	client, err := NewStreamingClient(*host, *port, *language, *prng, *verbose)
	if err != nil {
		log.Fatalf("Failed to create client: %v", err)
	}
	defer client.Close()

	log.Printf("%s (%s): playing %d match(es)...", *language, *prng, *matches)
	totalRounds := 0
	step := *matches / 10
	if step < 1 {
		step = 1
	}
	for i := 0; i < *matches; i++ {
		r, err := client.PlayMatch()
		if err != nil {
			log.Fatalf("match %d failed: %v", i+1, err)
		}
		totalRounds += r
		if *matches > 1 && (i+1)%step == 0 {
			log.Printf("  %s: %d/%d matches", *language, i+1, *matches)
		}
	}
	log.Printf("%s done: %d matches, %d rounds", *language, *matches, totalRounds)
}
