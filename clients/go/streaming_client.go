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

// StreamingClient implements the "clean" streaming approach
type StreamingClient struct {
	conn           *grpc.ClientConn
	client         pb.StreamingArenaServiceClient
	languageName   string
	prngAlgo       string
	random         *rand.Rand
	roundsComplete int
}

// NewStreamingClient creates a new streaming client
func NewStreamingClient(host string, port int, languageName, prngAlgo string) (*StreamingClient, error) {
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
	}, nil
}

// Close closes the connection
func (c *StreamingClient) Close() {
	if c.conn != nil {
		c.conn.Close()
	}
}

// Play starts the match
func (c *StreamingClient) Play() error {
	ctx := context.Background()
	log.Printf("Streaming Client starting: %s (%s)", c.languageName, c.prngAlgo)

	// Create bidirectional stream
	stream, err := c.client.Battle(ctx)
	if err != nil {
		return fmt.Errorf("failed to create stream: %v", err)
	}

	// Send handshake
	err = stream.Send(&pb.BattleRequest{
		Payload: &pb.BattleRequest_Handshake{
			Handshake: &pb.Handshake{
				LanguageName:  c.languageName,
				PrngAlgorithm: c.prngAlgo,
			},
		},
	})
	if err != nil {
		return fmt.Errorf("failed to send handshake: %v", err)
	}

	// Process server updates
	for {
		update, err := stream.Recv()
		if err == io.EOF {
			log.Println("Stream closed by server")
			break
		}
		if err != nil {
			return fmt.Errorf("receive error: %v", err)
		}

		// Handle different update types
		switch payload := update.Payload.(type) {
		case *pb.BattleResponse_Status:
			status := payload.Status
			log.Printf("Status: %s", status)

			if status == "MATCH_COMPLETE" {
				log.Printf("Match completed! Total rounds: %d", c.roundsComplete)
				return stream.CloseSend()
			} else if status == "OPPONENT_DISCONNECTED" {
				log.Println("Opponent disconnected")
				return stream.CloseSend()
			}

		case *pb.BattleResponse_Trigger:
			// Server requesting a move - respond immediately
			move := int32(c.random.Intn(3)) // 0=Rock, 1=Paper, 2=Scissors

			err = stream.Send(&pb.BattleRequest{
				Payload: &pb.BattleRequest_Move{
					Move: &pb.Move{
						Move: move,
					},
				},
			})
			if err != nil {
				return fmt.Errorf("failed to send move: %v", err)
			}

		case *pb.BattleResponse_Result:
			// Round result received
			c.roundsComplete++
			result := payload.Result
			if c.roundsComplete%100 == 0 {
				log.Printf("Round %d: %s", result.GetRoundId(), result.GetOutcome())
			}
		}
	}

	return nil
}

func main() {
	host := flag.String("host", "localhost", "Arena server host")
	port := flag.Int("port", 9000, "Arena server port")
	language := flag.String("language", "Go-1.21", "Language name")
	prng := flag.String("prng", "math/rand", "PRNG algorithm")
	flag.Parse()

	client, err := NewStreamingClient(*host, *port, *language, *prng)
	if err != nil {
		log.Fatalf("Failed to create client: %v", err)
	}
	defer client.Close()

	if err := client.Play(); err != nil {
		log.Fatalf("Play failed: %v", err)
	}
}