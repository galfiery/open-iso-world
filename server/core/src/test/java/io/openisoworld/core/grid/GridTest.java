package io.openisoworld.core.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class GridTest {

    private static final Grid GRID = Grid.parse(
            "....",
            "..#.",
            "#...");

    // Reading the drawing

    @Test
    void readsTheSizeFromTheDrawing() {
        assertEquals(4, GRID.width());
        assertEquals(3, GRID.height());
    }

    @Test
    void readsRowsAsYAndColumnsAsX() {
        assertFalse(GRID.isWalkable(new Tile(2, 1)));
        assertFalse(GRID.isWalkable(new Tile(0, 2)));
        assertTrue(GRID.isWalkable(new Tile(1, 2)));
    }

    @Test
    void listsBlockedTilesRowByRow() {
        assertEquals(List.of(new Tile(2, 1), new Tile(0, 2)), GRID.blockedTiles());
    }

    @Test
    void aSingleTileIsAValidGrid() {
        Grid grid = Grid.parse(".");

        assertEquals(1, grid.width());
        assertEquals(1, grid.height());
        assertTrue(grid.isWalkable(new Tile(0, 0)));
    }

    // Borders

    @Test
    void cornersAreInside() {
        assertTrue(GRID.contains(new Tile(0, 0)));
        assertTrue(GRID.contains(new Tile(3, 0)));
        assertTrue(GRID.contains(new Tile(0, 2)));
        assertTrue(GRID.contains(new Tile(3, 2)));
    }

    @Test
    void tilesPastTheBordersAreOutside() {
        assertFalse(GRID.contains(new Tile(-1, 0)));
        assertFalse(GRID.contains(new Tile(0, -1)));
        assertFalse(GRID.contains(new Tile(4, 0)));
        assertFalse(GRID.contains(new Tile(0, 3)));
    }

    @Test
    void tilesOutsideAreNotWalkable() {
        assertFalse(GRID.isWalkable(new Tile(4, 0)));
        assertFalse(GRID.isWalkable(new Tile(-1, -1)));
    }

    @Test
    void acceptsTheLargestSide() {
        String row = ".".repeat(Grid.MAX_SIDE);

        assertEquals(Grid.MAX_SIDE, Grid.parse(row).width());
    }

    // Invalid drawings

    @Test
    void refusesNoRows() {
        assertThrows(IllegalArgumentException.class, Grid::parse);
    }

    @Test
    void refusesAnEmptyRow() {
        assertThrows(IllegalArgumentException.class, () -> Grid.parse(""));
    }

    @Test
    void refusesRowsOfDifferentLength() {
        var error = assertThrows(IllegalArgumentException.class, () -> Grid.parse("...", ".."));

        assertTrue(error.getMessage().contains("Row 1"), error.getMessage());
    }

    @Test
    void refusesAnUnknownSymbolAndSaysWhere() {
        var error = assertThrows(IllegalArgumentException.class, () -> Grid.parse("...", ".x."));

        assertTrue(error.getMessage().contains("row 1, column 1"), error.getMessage());
    }

    @Test
    void refusesAWideGrid() {
        String row = ".".repeat(Grid.MAX_SIDE + 1);

        assertThrows(IllegalArgumentException.class, () -> Grid.parse(row));
    }

    @Test
    void refusesATallGrid() {
        String[] rows = new String[Grid.MAX_SIDE + 1];
        Arrays.fill(rows, ".");

        assertThrows(IllegalArgumentException.class, () -> Grid.parse(rows));
    }
}
