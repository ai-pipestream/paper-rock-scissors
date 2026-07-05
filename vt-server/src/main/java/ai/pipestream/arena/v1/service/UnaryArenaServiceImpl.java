package ai.pipestream.arena.v1.service;

import ai.pipestream.arena.v1.repository.ArenaRepository;
import ai.pipestream.tourney.unary.v1.*;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Unary gRPC service — virtual-threads variant.
 *
 * Compare with :mutiny-server's {@code UnaryArenaServiceImpl}, which builds a
 * {@code Uni} chain per call. Here the methods carry {@code @RunOnVirtualThread},
 * so Quarkus runs each invocation on a fresh virtual thread. That means the
 * {@link ArenaRepository} call — plain, blocking, transactional JDBC — executes
 * without ever blocking a platform/event-loop thread. The only "reactive" left is
 * wrapping the already-computed response in {@code Uni.createFrom().item(...)} to
 * satisfy the generated Mutiny interface.
 */
@GrpcService
@Singleton
public class UnaryArenaServiceImpl implements UnaryArenaService {

    @Inject
    ArenaRepository arena;

    @Override
    @RunOnVirtualThread
    public Uni<RegisterResponse> register(RegisterRequest request) {
        return Uni.createFrom().item(arena.register(request));
    }

    @Override
    @RunOnVirtualThread
    public Uni<SubmitMoveResponse> submitMove(SubmitMoveRequest request) {
        return Uni.createFrom().item(arena.submitMove(request));
    }

    @Override
    @RunOnVirtualThread
    public Uni<CheckRoundResultResponse> checkRoundResult(CheckRoundResultRequest request) {
        return Uni.createFrom().item(arena.checkRoundResult(request));
    }
}
