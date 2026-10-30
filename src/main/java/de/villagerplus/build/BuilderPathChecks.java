package de.villagerplus.build;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** World regression checks for continuous door-to-village paths around buildings. */
public final class BuilderPathChecks {
    private BuilderPathChecks() { }

    public static int run(ServerLevel level, BlockPos origin) {
        Map<BlockPos, Cell> before = new LinkedHashMap<>();
        BlockPos start = origin.offset(-10, 1, 0), goal = origin.offset(10, 1, 0);
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-16, 0, -10), origin.offset(16, 5, 10))) {
            BlockPos pos = p.immutable();
            level.getChunkAt(pos);
            BlockEntity be = level.getBlockEntity(pos);
            before.put(pos, new Cell(level.getBlockState(pos), be == null ? null : be.saveWithFullMetadata(level.registryAccess())));
        }
        try {
            for (int x = -15; x <= 15; x++) for (int z = -9; z <= 9; z++) {
                level.setBlock(origin.offset(x, 0, z), Blocks.DIRT.defaultBlockState(), 3);
                level.setBlock(origin.offset(x, 1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                for (int y = 2; y <= 5; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            }
            // A solid village building blocks the direct line; the path must go around its footprint.
            for (int z = -3; z <= 3; z++) for (int y = 1; y <= 3; y++)
                level.setBlock(origin.offset(0, y, z), Blocks.OAK_PLANKS.defaultBlockState(), 3);

            Builder.Paths.lay(level, start, goal);
            Set<BlockPos> path = new HashSet<>();
            for (int x = -15; x <= 15; x++) for (int z = -9; z <= 9; z++) {
                BlockPos p = origin.offset(x, 1, z);
                if (level.getBlockState(p).is(Blocks.DIRT_PATH)) path.add(p);
            }
            require(!path.isEmpty(), "path planner lays a village connection");
            require(path.stream().anyMatch(p -> p.distManhattan(start) <= 1)
                && path.stream().anyMatch(p -> p.distManhattan(goal) <= 1), "path reaches both endpoint approaches");
            require(path.stream().noneMatch(p -> p.getX() == origin.getX() && Math.abs(p.getZ() - origin.getZ()) <= 3),
                "path routes around the solid building without replacing it");
            require(connected(path, start, goal), "path tiles form one continuous four-neighbor route");
            for (int z = -3; z <= 3; z++) for (int y = 1; y <= 3; y++)
                require(level.getBlockState(origin.offset(0, y, z)).is(Blocks.OAK_PLANKS), "building blocks remain untouched");
            return 5;
        } finally {
            for (BlockPos pos : before.keySet()) level.removeBlockEntity(pos);
            for (BlockPos pos : before.keySet()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            before.forEach((pos, cell) -> level.setBlock(pos, cell.state(), 3));
            before.forEach((pos, cell) -> {
                if (cell.data() != null) {
                    BlockEntity be = BlockEntity.loadStatic(pos, cell.state(), cell.data().copy(), level.registryAccess());
                    if (be != null) level.setBlockEntity(be);
                }
            });
        }
    }

    private record Cell(BlockState state, CompoundTag data) { }

    private static boolean connected(Set<BlockPos> path, BlockPos start, BlockPos goal) {
        BlockPos source = path.stream().filter(p -> p.distManhattan(start) <= 1).findFirst().orElse(null);
        if (source == null) return false;
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(source); visited.add(source);
        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            if (current.distManhattan(goal) <= 1) return true;
            for (BlockPos next : new BlockPos[]{current.north(), current.south(), current.east(), current.west()})
                if (path.contains(next) && visited.add(next)) queue.addLast(next);
        }
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Builder path: " + message);
    }
}
