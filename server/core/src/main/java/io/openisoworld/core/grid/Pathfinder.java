package io.openisoworld.core.grid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * Finds the shortest walkable path between two tiles with A*.
 *
 * <p>Players move in eight directions. A diagonal step is allowed only when both tiles beside it
 * are walkable, so a path never cuts the corner of a blocked tile. The same input always gives the
 * same path.
 */
public final class Pathfinder {

    private static final int STRAIGHT_COST = 10;
    private static final int DIAGONAL_COST = 14; // about 10 * sqrt(2)

    /** The four straight directions first, then the four diagonals: a fixed order keeps paths stable. */
    private static final int[][] DIRECTIONS = {
        {1, 0}, {0, 1}, {-1, 0}, {0, -1},
        {1, 1}, {-1, 1}, {-1, -1}, {1, -1}
    };

    /** A tile waiting to be explored: its cost from the start, its estimated total cost and when it was queued. */
    private record Node(Tile tile, int cost, int estimate, long order) {}

    private Pathfinder() {}

    /**
     * Returns the path from start to goal, both included, or empty if the goal cannot be reached.
     * A path from a tile to itself is that single tile. The path is empty if start or goal is not
     * walkable.
     */
    public static Optional<List<Tile>> find(Grid grid, Tile start, Tile goal) {
        if (!grid.isWalkable(start) || !grid.isWalkable(goal)) {
            return Optional.empty();
        }
        // Ties on the estimate go to the node queued first, so the result never depends on chance.
        var open = new PriorityQueue<Node>(
                Comparator.comparingInt(Node::estimate).thenComparingLong(Node::order));
        Map<Tile, Integer> bestCost = new HashMap<>();
        Map<Tile, Tile> cameFrom = new HashMap<>();
        long order = 0;

        bestCost.put(start, 0);
        open.add(new Node(start, 0, distance(start, goal), order++));

        while (!open.isEmpty()) {
            Node node = open.poll();
            if (node.tile().equals(goal)) {
                return Optional.of(pathTo(goal, cameFrom));
            }
            if (node.cost() > bestCost.get(node.tile())) {
                continue; // A cheaper way to this tile was found after this node was queued.
            }
            for (int[] direction : DIRECTIONS) {
                Tile next = new Tile(node.tile().x() + direction[0], node.tile().y() + direction[1]);
                if (!canStep(grid, node.tile(), next)) {
                    continue;
                }
                int cost = node.cost() + stepCost(node.tile(), next);
                Integer known = bestCost.get(next);
                if (known == null || cost < known) {
                    bestCost.put(next, cost);
                    cameFrom.put(next, node.tile());
                    open.add(new Node(next, cost, cost + distance(next, goal), order++));
                }
            }
        }
        return Optional.empty();
    }

    /** Whether a player can step from a tile to a neighbouring one without cutting a corner. */
    private static boolean canStep(Grid grid, Tile from, Tile to) {
        if (!grid.isWalkable(to)) {
            return false;
        }
        boolean diagonal = from.x() != to.x() && from.y() != to.y();
        return !diagonal
                || (grid.isWalkable(new Tile(to.x(), from.y())) && grid.isWalkable(new Tile(from.x(), to.y())));
    }

    private static int stepCost(Tile from, Tile to) {
        boolean diagonal = from.x() != to.x() && from.y() != to.y();
        return diagonal ? DIAGONAL_COST : STRAIGHT_COST;
    }

    /**
     * The octile distance: the cost of the shortest path on an empty grid. It never overestimates,
     * so A* always finds a shortest path.
     */
    private static int distance(Tile from, Tile to) {
        int dx = Math.abs(from.x() - to.x());
        int dy = Math.abs(from.y() - to.y());
        return STRAIGHT_COST * (dx + dy) + (DIAGONAL_COST - 2 * STRAIGHT_COST) * Math.min(dx, dy);
    }

    /** Walks back from the goal to the start, then reverses the tiles. */
    private static List<Tile> pathTo(Tile goal, Map<Tile, Tile> cameFrom) {
        List<Tile> path = new ArrayList<>();
        for (Tile tile = goal; tile != null; tile = cameFrom.get(tile)) {
            path.add(tile);
        }
        Collections.reverse(path);
        return List.copyOf(path);
    }
}
