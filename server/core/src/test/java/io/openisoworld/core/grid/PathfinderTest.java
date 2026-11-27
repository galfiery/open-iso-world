package io.openisoworld.core.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PathfinderTest {

    private static final Grid EMPTY = Grid.parse(
            ".....",
            ".....",
            ".....",
            ".....",
            ".....");

    // Simple paths

    @Test
    void aPathToTheSameTileIsThatTile() {
        assertEquals(List.of(tile(2, 2)), find(EMPTY, tile(2, 2), tile(2, 2)));
    }

    @Test
    void walksAStraightLine() {
        assertEquals(
                List.of(tile(0, 1), tile(1, 1), tile(2, 1), tile(3, 1), tile(4, 1)),
                find(EMPTY, tile(0, 1), tile(4, 1)));
    }

    @Test
    void walksADiagonalLine() {
        assertEquals(
                List.of(tile(0, 0), tile(1, 1), tile(2, 2), tile(3, 3), tile(4, 4)),
                find(EMPTY, tile(0, 0), tile(4, 4)));
    }

    // Obstacles

    @Test
    void goesAroundAWallByTheShortestWay() {
        Grid grid = Grid.parse(
                "......",
                "..#...",
                "..#...",
                "..#...",
                "......");

        List<Tile> path = find(grid, tile(0, 2), tile(4, 2));

        assertValid(grid, path, tile(0, 2), tile(4, 2));
        // The wall can only be passed along the top row, from (1,0) to (3,0): a diagonal next to it
        // would cut its corner. One diagonal and one straight step on each side: 24 + 20 + 24.
        assertEquals(68, cost(path));
    }

    @Test
    void neverCutsTheCornerOfABlockedTile() {
        Grid grid = Grid.parse(
                ".#",
                "..");

        assertEquals(List.of(tile(0, 0), tile(0, 1), tile(1, 1)), find(grid, tile(0, 0), tile(1, 1)));
    }

    @Test
    void cannotSqueezeBetweenTwoDiagonalBlocks() {
        Grid grid = Grid.parse(
                ".#",
                "#.");

        assertTrue(Pathfinder.find(grid, tile(0, 0), tile(1, 1)).isEmpty());
    }

    @Test
    void walksAlongTheBorderWithoutLeavingTheGrid() {
        Grid grid = Grid.parse(
                ".....",
                ".###.",
                ".....");

        List<Tile> path = find(grid, tile(0, 1), tile(4, 1));

        assertValid(grid, path, tile(0, 1), tile(4, 1));
        // Along the top or the bottom row, straight steps only, since every diagonal next to the
        // wall would cut its corner: 10 + 40 + 10.
        assertEquals(60, cost(path));
    }

    // No path

    @Test
    void findsNothingWhenTheGoalIsWalledIn() {
        Grid grid = Grid.parse(
                ".....",
                ".###.",
                ".#.#.",
                ".###.",
                ".....");

        assertTrue(Pathfinder.find(grid, tile(0, 0), tile(2, 2)).isEmpty());
    }

    @Test
    void findsNothingWhenTheGoalIsBlocked() {
        Grid grid = Grid.parse("..#");

        assertTrue(Pathfinder.find(grid, tile(0, 0), tile(2, 0)).isEmpty());
    }

    @Test
    void findsNothingWhenTheGoalIsOutsideTheGrid() {
        assertTrue(Pathfinder.find(EMPTY, tile(0, 0), tile(5, 0)).isEmpty());
        assertTrue(Pathfinder.find(EMPTY, tile(0, 0), tile(-1, 0)).isEmpty());
    }

    // Determinism

    @Test
    void alwaysReturnsTheSamePath() {
        // From (0,0) to (4,2) there are several shortest paths; the result must not change.
        List<Tile> first = find(EMPTY, tile(0, 0), tile(4, 2));

        assertValid(EMPTY, first, tile(0, 0), tile(4, 2));
        assertEquals(48, cost(first));
        for (int i = 0; i < 10; i++) {
            assertEquals(first, find(EMPTY, tile(0, 0), tile(4, 2)));
        }
    }

    // Helpers

    private static Tile tile(int x, int y) {
        return new Tile(x, y);
    }

    private static List<Tile> find(Grid grid, Tile start, Tile goal) {
        return Pathfinder.find(grid, start, goal).orElseThrow();
    }

    /** Checks the ends of the path and that every step goes to a walkable neighbour. */
    private static void assertValid(Grid grid, List<Tile> path, Tile start, Tile goal) {
        assertEquals(start, path.getFirst());
        assertEquals(goal, path.getLast());
        for (int i = 1; i < path.size(); i++) {
            Tile from = path.get(i - 1);
            Tile to = path.get(i);
            assertTrue(grid.isWalkable(to), "Not walkable: " + to);
            int dx = Math.abs(from.x() - to.x());
            int dy = Math.abs(from.y() - to.y());
            assertTrue(dx <= 1 && dy <= 1 && dx + dy > 0, "Not a neighbour: " + from + " -> " + to);
            if (dx == 1 && dy == 1) {
                assertTrue(
                        grid.isWalkable(tile(to.x(), from.y())) && grid.isWalkable(tile(from.x(), to.y())),
                        "Cuts a corner: " + from + " -> " + to);
            }
        }
    }

    /** The cost of a path, with the weights of the pathfinder: 10 for a straight step, 14 for a diagonal. */
    private static int cost(List<Tile> path) {
        int cost = 0;
        for (int i = 1; i < path.size(); i++) {
            boolean diagonal = path.get(i - 1).x() != path.get(i).x() && path.get(i - 1).y() != path.get(i).y();
            cost += diagonal ? 14 : 10;
        }
        return cost;
    }
}
