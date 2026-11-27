package io.openisoworld.core.grid;

import java.util.ArrayList;
import java.util.List;

/**
 * The floor of a room: its size and the tiles nobody can walk on. Immutable.
 *
 * <p>A grid is written as a drawing, one string per row: {@code .} is floor, {@code #} is
 * blocked. The first row is {@code y = 0} and the first character of each row is {@code x = 0}.
 *
 * <pre>{@code
 * Grid grid = Grid.parse(
 *         "......",
 *         "..##..",
 *         "......");
 * }</pre>
 */
public final class Grid {

    /** The longest side a grid may have. It bounds the work of a path search and the room state. */
    public static final int MAX_SIDE = 64;

    private static final char FLOOR = '.';
    private static final char BLOCKED = '#';

    private final int width;
    private final int height;
    private final boolean[][] blocked; // blocked[y][x]

    private Grid(boolean[][] blocked) {
        this.height = blocked.length;
        this.width = blocked[0].length;
        this.blocked = blocked;
    }

    /** Reads a grid from its drawing. Throws {@link IllegalArgumentException} if the drawing is invalid. */
    public static Grid parse(String... rows) {
        if (rows.length == 0) {
            throw new IllegalArgumentException("A grid needs at least one row");
        }
        int width = rows[0].length();
        if (width == 0) {
            throw new IllegalArgumentException("A grid row must not be empty");
        }
        if (width > MAX_SIDE || rows.length > MAX_SIDE) {
            throw new IllegalArgumentException("A grid side must be at most " + MAX_SIDE + " tiles");
        }
        boolean[][] blocked = new boolean[rows.length][width];
        for (int y = 0; y < rows.length; y++) {
            blocked[y] = parseRow(rows[y], y, width);
        }
        return new Grid(blocked);
    }

    private static boolean[] parseRow(String row, int y, int width) {
        if (row.length() != width) {
            throw new IllegalArgumentException(
                    "Row " + y + " has " + row.length() + " tiles, but row 0 has " + width);
        }
        boolean[] blocked = new boolean[width];
        for (int x = 0; x < width; x++) {
            char symbol = row.charAt(x);
            if (symbol != FLOOR && symbol != BLOCKED) {
                throw new IllegalArgumentException(
                        "Unknown symbol '" + symbol + "' at row " + y + ", column " + x
                                + "; use '" + FLOOR + "' or '" + BLOCKED + "'");
            }
            blocked[x] = symbol == BLOCKED;
        }
        return blocked;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Whether the tile lies inside the grid. */
    public boolean contains(Tile tile) {
        return tile.x() >= 0 && tile.x() < width && tile.y() >= 0 && tile.y() < height;
    }

    /** Whether a player can stand on the tile: it lies inside the grid and is not blocked. */
    public boolean isWalkable(Tile tile) {
        return contains(tile) && !blocked[tile.y()][tile.x()];
    }

    /** The blocked tiles, row by row and then column by column. */
    public List<Tile> blockedTiles() {
        List<Tile> tiles = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (blocked[y][x]) {
                    tiles.add(new Tile(x, y));
                }
            }
        }
        return List.copyOf(tiles);
    }
}
