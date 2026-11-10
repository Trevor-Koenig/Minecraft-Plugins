// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.cells;

import java.util.List;

/**
 * A tier of cells: one cell block, and the sizes a cell in it grows through. The first size of the first tier is
 * the free starting cell; the first size of every later tier is bought by moving to a new cell in that tier.
 */
public record CellTier(int number, List<Size> sizes) {

    /** One cell size: the room's floor (width side to side, depth front to back), its height, and its price. */
    public record Size(int width, int depth, int height, double price) {

        /** "5x5": the floor, which is what players think of as the cell's size. */
        public String floor() {
            return width + "x" + depth;
        }
    }

    public int maxWidth() { return sizes.stream().mapToInt(Size::width).max().orElse(0); }
    public int maxDepth() { return sizes.stream().mapToInt(Size::depth).max().orElse(0); }
    public int maxHeight() { return sizes.stream().mapToInt(Size::height).max().orElse(0); }
}
