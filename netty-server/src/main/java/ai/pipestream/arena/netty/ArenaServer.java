package ai.pipestream.arena.netty;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Bootstraps a plain grpc-java / Netty server hosting {@link StreamingArenaServiceImpl}.
 *
 * <p>This is the "old school gRPC" control group: no Quarkus, no Vert.x, no Mutiny —
 * just {@link NettyServerBuilder}. Every performance-relevant knob is configurable via
 * env var (or {@code -D} system property) and echoed at startup, so each benchmark run
 * records exactly what it measured. The most interesting knob is the HTTP/2 flow-control
 * window: grpc-java defaults to 1&nbsp;MB, whereas the Quarkus/Vert.x unified server
 * defaults to 64&nbsp;KB — set {@code ARENA_FLOW_CONTROL_WINDOW=65535} here to compare
 * like-for-like.
 */
public final class ArenaServer {

    private ArenaServer() {}

    /** env var wins, then {@code -D} system property, then default. Blank counts as unset. */
    private static String cfg(String env, String prop, String def) {
        String v = System.getenv(env);
        if (v == null || v.isBlank()) v = System.getProperty(prop);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    private static int cfgInt(String env, String prop, int def) {
        String v = cfg(env, prop, null);
        return v == null ? def : Integer.parseInt(v);
    }

    public static void main(String[] args) throws Exception {
        int port = cfgInt("ARENA_PORT", "arena.port", 9000);
        int totalRounds = cfgInt("ARENA_TOTAL_ROUNDS", "arena.total-rounds", 1000);
        int window = cfgInt("ARENA_FLOW_CONTROL_WINDOW", "arena.flow-control-window", -1);
        int maxStreams = cfgInt("ARENA_MAX_CONCURRENT_STREAMS", "arena.max-concurrent-streams", -1);
        boolean vt = Boolean.parseBoolean(cfg("ARENA_VIRTUAL_THREADS", "arena.virtual-threads", "false"));

        NettyServerBuilder builder = NettyServerBuilder.forPort(port)
                .addService(new StreamingArenaServiceImpl(totalRounds));

        if (window > 0) builder.flowControlWindow(window);
        if (maxStreams > 0) builder.maxConcurrentCallsPerConnection(maxStreams);
        if (vt) builder.executor(Executors.newVirtualThreadPerTaskExecutor());

        Server server = builder.build().start();

        System.out.println("========================================================");
        System.out.println(" Vanilla grpc-java / Netty arena  (no Quarkus)");
        System.out.println("   port                    = " + port);
        System.out.println("   total-rounds            = " + totalRounds);
        System.out.println("   flow-control-window     = "
                + (window > 0 ? window + " bytes" : "grpc-default (1 MB)"));
        System.out.println("   max-concurrent-streams  = "
                + (maxStreams > 0 ? maxStreams : "grpc-default (~100)"));
        System.out.println("   request executor        = "
                + (vt ? "virtual threads (newVirtualThreadPerTaskExecutor)"
                      : "grpc-default (cached thread pool)"));
        System.out.println("   leaderboard             = in-memory (no database)");
        System.out.println("========================================================");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down arena ...");
            try {
                server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        server.awaitTermination();
    }
}
