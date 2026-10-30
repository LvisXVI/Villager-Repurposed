package de.villagerplus.village;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** In-world regression checks for terrain-aware village roads. Every touched cell is restored. */
public final class RoadTerrainChecks {
    private RoadTerrainChecks() { }

    public static int run(ServerLevel level, BlockPos origin) {
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        BlockPos tree = origin, placedLog = origin.east(4), water = origin.east(8), abyss = origin.east(12);
        snapshot(level, before, tree, 0, 8);
        snapshot(level, before, placedLog, 0, 4);
        snapshot(level, before, water, -1, 6);
        snapshot(level, before, abyss, -1, 28);
        try {
            level.setBlock(tree.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            for (int y = 0; y < 5; y++) level.setBlock(tree.above(y), Blocks.OAK_LOG.defaultBlockState(), 3);
            level.setBlock(tree.offset(1, 4, 0), Blocks.OAK_LEAVES.defaultBlockState(), 3);
            Roads.pavePath(level, tree.getX(), tree.getZ());
            require(level.getBlockState(tree.below()).is(Blocks.DIRT_PATH)
                && level.getBlockState(tree).isAir() && level.getBlockState(tree.above(4)).isAir(),
                "path clears a rooted, leafy natural trunk and paves its soil");

            level.setBlock(placedLog.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            level.setBlock(placedLog, Blocks.OAK_LOG.defaultBlockState(), 3);
            Roads.pavePath(level, placedLog.getX(), placedLog.getZ());
            require(level.getBlockState(placedLog).is(Blocks.OAK_LOG)
                && level.getBlockState(placedLog.below()).is(Blocks.GRASS_BLOCK),
                "path leaves isolated placed timber and its ground untouched");

            level.setBlock(water.below(), Blocks.STONE.defaultBlockState(), 3);
            for (int y = 0; y < 4; y++) level.setBlock(water.above(y), Blocks.WATER.defaultBlockState(), 3);
            Roads.pavePath(level, water.getX(), water.getZ());
            require(level.getBlockState(water.above(3)).is(Blocks.SPRUCE_PLANKS)
                && level.getBlockState(water.above(2)).is(Blocks.SPRUCE_LOG)
                && level.getBlockState(water.below()).is(Blocks.STONE),
                "shallow water gets a plank deck and log piers down to solid bed");

            level.setBlock(abyss.below(), Blocks.STONE.defaultBlockState(), 3);
            for (int y = 0; y < 27; y++) level.setBlock(abyss.above(y), Blocks.WATER.defaultBlockState(), 3);
            Roads.pavePath(level, abyss.getX(), abyss.getZ());
            require(level.getBlockState(abyss).is(Blocks.WATER)
                && level.getBlockState(abyss.above()).is(Blocks.WATER),
                "deep water without reachable footing stays unchanged");
            return 4;
        } finally {
            before.forEach((pos, state) -> level.setBlock(pos, state, 3));
        }
    }

    private static void snapshot(ServerLevel level, Map<BlockPos, BlockState> before, BlockPos column, int min, int max) {
        for (int y = min; y <= max; y++) {
            BlockPos pos = column.above(y).immutable();
            level.getChunkAt(pos);
            before.putIfAbsent(pos, level.getBlockState(pos));
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Road terrain: " + message);
    }
}
