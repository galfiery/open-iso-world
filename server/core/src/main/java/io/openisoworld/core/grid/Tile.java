package io.openisoworld.core.grid;

/**
 * A tile of the room grid, in grid coordinates. Screen coordinates never reach the server.
 *
 * <p>Any pair of integers is a tile: a requested tile may lie outside the room, and the
 * {@link Grid} decides whether it does.
 */
public record Tile(int x, int y) {}
