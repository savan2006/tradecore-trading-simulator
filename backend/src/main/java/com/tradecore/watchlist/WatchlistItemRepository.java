package com.tradecore.watchlist;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchlistItemRepository extends JpaRepository<WatchlistItem, UUID> {
    @EntityGraph(attributePaths = {"instrument", "watchlist"})
    List<WatchlistItem> findAllByWatchlist_IdOrderBySortOrderAscCreatedAtAsc(UUID watchlistId);
    @EntityGraph(attributePaths = {"instrument", "watchlist"})
    List<WatchlistItem> findAllByWatchlist_User_EmailOrderByWatchlist_IdAscSortOrderAscCreatedAtAsc(String email);
    Optional<WatchlistItem> findByWatchlist_IdAndInstrument_Id(UUID watchlistId, UUID instrumentId);
    int countByWatchlist_Id(UUID watchlistId);
    void deleteByWatchlist_IdAndInstrument_Id(UUID watchlistId, UUID instrumentId);
}
