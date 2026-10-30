package de.villagerplus.village;

import de.villagerplus.build.Blueprints;
import de.villagerplus.build.ZoneAwareSites;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** World checks for centre-first civic building and plaza placement. */
public final class CivicCenterChecks {
    private CivicCenterChecks() { }
    private record Cell(BlockState state, CompoundTag data) { }

    public static int run(ServerLevel level, VillageRef ref) {
        Map<BlockPos, Cell> before = new LinkedHashMap<>();
        BlockPos center = ref.bell().offset(40, 0, 40);
        int min = -61, max = 61;
        for (int x = min; x <= max; x++) for (int z = min; z <= max; z++) for (int y = -2; y <= 7; y++) {
            BlockPos pos = center.offset(x, y, z).immutable(); level.getChunkAt(pos);
            BlockEntity be = level.getBlockEntity(pos);
            before.put(pos, new Cell(level.getBlockState(pos), be == null ? null : be.saveWithFullMetadata(level.registryAccess())));
        }
        try {
            for (int x = min; x <= max; x++) for (int z = min; z <= max; z++) {
                level.setBlock(center.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                for (int y = 0; y <= 6; y++) level.setBlock(center.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
            // A civic obstruction occupies the exact map centre. Both searches must find a nearby usable plot around it.
            level.setBlock(center.below(), Blocks.COBBLESTONE.defaultBlockState(), 3);
            BlockPos townHall = ZoneAwareSites.findCentral(level, ref, Blueprints.TOWNHALL, center,
                net.minecraft.util.RandomSource.create(991), 32, -7, 7, -7, 7, 10, p -> true);
            require(townHall != null && !townHall.equals(center) && townHall.distSqr(center) < 30 * 30,
                "town hall search stays near the measured civic centre and avoids its blocked footprint");

            BlockPos plaza = Plaza.findCentralSite(level, center, ref.bell(), ref.bell().asLong());
            require(plaza != null && plaza.distSqr(center) >= 12 * 12 && plaza.distSqr(center) < 34 * 34,
                "plaza search moves around existing central construction and stays in the inner village");
            return 2;
        } finally {
            before.keySet().forEach(level::removeBlockEntity);
            before.forEach((pos, cell) -> level.setBlock(pos, cell.state(), 3));
            before.forEach((pos, cell) -> {
                if (cell.data() != null) {
                    BlockEntity be = BlockEntity.loadStatic(pos, cell.state(), cell.data().copy(), level.registryAccess());
                    if (be != null) level.setBlockEntity(be);
                }
            });
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Civic centre: " + message);
    }
}
