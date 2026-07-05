package ai.pipestream.arena.v1.repository;

import ai.pipestream.arena.v1.model.MatchStatistics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * The one blocking database touch of the streaming arena: persisting a match's
 * final statistics. In :mutiny-server this is {@code Panache.withTransaction(...)}
 * returning a {@code Uni}; here it is an ordinary {@code @Transactional} method,
 * invoked on a virtual thread from the streaming service so the blocking JDBC write
 * never lands on a reactive emitter thread.
 */
@ApplicationScoped
public class StreamStatsRepository {

    @Transactional
    public void save(MatchStatistics stats) {
        stats.persist();
    }

    /** Every completed streaming match, for the arena leaderboard aggregation. */
    @Transactional
    public java.util.List<MatchStatistics> allStreamingMatches() {
        return MatchStatistics.list("matchType", "STREAMING");
    }
}
