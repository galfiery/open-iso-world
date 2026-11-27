package io.openisoworld.core.room;

import io.openisoworld.core.grid.Tile;
import java.util.List;

/** A player as the others see them: the tile they stand on and the tiles still to walk. */
public record PlayerView(String playerId, Tile position, List<Tile> path) {
    public PlayerView {
        path = List.copyOf(path);
    }
}
