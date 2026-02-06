package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"math/rand"
	"time"

	pb "github.com/ai-pipestream/paper-rock-scissors/clients/go/pb/tourney_unary"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

// UnaryClient implements the "painful" polling approach
type UnaryClient struct {
	conn         *grpc.ClientConn
	client       pb.UnaryArenaClient
	languageName string
	prngAlgo     string
	random       *rand.Rand
}

// NewUnaryClient creates a new unary client
func NewUnaryClient(host string, port int, languageName, prngAlgo string) (*UnaryClient, error) {
	address := fmt.Sprintf("%s:%d", host, port)
	conn, err := grpc.Dial(address, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		return nil, fmt.Errorf("failed to connect: %v", err)
	}

	return &UnaryClient{
		conn:         conn,
		client:       pb.NewUnaryArenaClient(conn),
		languageName: languageName,
		prngAlgo:     prngAlgo,
		random:       rand.New(rand.NewSource(time.Now().UnixNano())),
	}, nil
}

// Close closes the connection
func (c *UnaryClient) Close() {
	if c.conn != nil {
		c.conn.Close()
	}
}

// Play starts the match
func (c *UnaryClient) Play() error {
	ctx := context.Background()
	log.Printf("Unary Client starting: %s (%s)", c.languageName, c.prngAlgo)

	// Step 1: Register
	regResp, err := c.client.Register(ctx, &pb.RegistrationRequest{
		LanguageName:  c.languageName,
		PrngAlgorithm: c.prngAlgo,
	})
	if err != nil {
		return fmt.Errorf("registration failed: %v", err)
	}

	matchID := regResp.GetMatchId()
	log.Printf("Registered with matchId: %s, Status: %s", matchID, regResp.GetStatus())

	// Wait for opponent if needed
	if regResp.GetStatus() == "WAITING_FOR_OPPONENT" {
		log.Println("Waiting for opponent...")
		time.Sleep(2 * time.Second)
	}

	log.Println("Starting match...")

	// Play rounds
	for round := int32(1); round <= 1000; round++ {
		// Step 2: Submit move
		move := int32(c.random.Intn(3)) // 0=Rock, 1=Paper, 2=Scissors

		moveResp, err := c.client.SubmitMove(ctx, &pb.MoveRequest{
			MatchId:     matchID,
			RoundNumber: round,
			Move:        move,
		})
		if err != nil {
			return fmt.Errorf("submit move failed: %v", err)
		}

		if moveResp.GetStatus() == "GAME_OVER" {
			log.Println("Game is over")
			break
		}

		if moveResp.GetStatus() != "ACCEPTED" {
			log.Printf("Move not accepted: %s", moveResp.GetStatus())
			continue
		}

		// Step 3: Poll for result (THE PAINFUL PART)
		pollAttempts := 0
		var result *pb.ResultResponse
		for {
			pollAttempts++
			result, err = c.client.CheckRoundResult(ctx, &pb.ResultRequest{
				MatchId:     matchID,
				RoundNumber: round,
			})
			if err != nil {
				return fmt.Errorf("check result failed: %v", err)
			}

			if result.GetStatus() != "PENDING" {
				break
			}

			time.Sleep(10 * time.Millisecond) // Polling delay
		}

		if round%100 == 0 {
			log.Printf("Round %d: %s (Polls: %d)", round, result.GetOutcome(), pollAttempts)
		}
	}

	log.Println("Match completed!")
	return nil
}

func main() {
	host := flag.String("host", "localhost", "Arena server host")
	port := flag.Int("port", 9000, "Arena server port")
	language := flag.String("language", "Go-1.21", "Language name")
	prng := flag.String("prng", "math/rand", "PRNG algorithm")
	flag.Parse()

	client, err := NewUnaryClient(*host, *port, *language, *prng)
	if err != nil {
		log.Fatalf("Failed to create client: %v", err)
	}
	defer client.Close()

	if err := client.Play(); err != nil {
		log.Fatalf("Play failed: %v", err)
	}
}
