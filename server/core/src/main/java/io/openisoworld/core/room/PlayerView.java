package io.openisoworld.core.room;

import io.openisoworld.core.grid.Tile;
import java.util.List;
import java.util.Objects;

/** A player as the others see them: the tile they stand on and the tiles still to walk. */
public record PlayerView(String playerId, Tile position, List<Tile> path) {
    public PlayerView {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(position, "position");
        path = List.copyOf(path);
    }
}
