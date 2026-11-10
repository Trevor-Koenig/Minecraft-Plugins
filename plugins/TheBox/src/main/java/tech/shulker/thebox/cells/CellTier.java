// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.cells;

import java.util.List;
import org.bukkit.block.data.BlockData;

/**
 * A tier of cells: one cell block, and the sizes a cell in it grows through. The first size of the first tier is
 * the free starting cell; the first size of every later tier is bought by moving to a new cell in that tier.
 *
 * {@code front} is the tier's cell front (a door, a window, a button...), saved with /thebox cell savefront and put
 * on every cell of the tier each time it's rebuilt. Empty for a plain front wall.
 */
public record CellTier(int number, List<Size> sizes, List<FrontBlock> front) {

    /**
     * One block of a tier's front, by its place as you face the cell: {@code side} counted from the right edge of the
     * starting room (negative further right), {@code up} from the floor, {@code out} from the front wall (0) into the
     * front space (1, 2, ...) or the room (-1). {@code data} is stored as if the cell's front faced north, and turned
     * to each cell's direction when placed. Don't change it: copy it with {@code clone()} first.
     */
    public record FrontBlock(int side, int up, int out, BlockData data) {}

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
