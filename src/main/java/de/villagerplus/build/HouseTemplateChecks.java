package de.villagerplus.build;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** In-world check that small, recognizable village homes remain eligible as construction templates. */
public final class HouseTemplateChecks {
    private HouseTemplateChecks() { }

    public static int run(ServerLevel level, BlockPos bed) {
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(bed.offset(-2, -1, -2), bed.offset(14, 5, 5))) {
            BlockPos pos = p.immutable(); level.getChunkAt(pos); before.put(pos, level.getBlockState(pos));
        }
        try {
            for (BlockPos p : BlockPos.betweenClosed(bed.offset(0, -1, 0), bed.offset(3, -1, 3)))
                level.setBlock(p, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            // Compact 4x4 cottage: connected single-height shell, recognizable roof, door, and bed.
            for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) {
                if (x == 0 || x == 3 || z == 0 || z == 3) {
                    if (!(x == 1 && z == 0)) level.setBlock(bed.offset(x, 1, z), Blocks.SPRUCE_PLANKS.defaultBlockState(), 3);
                }
            }
            BlockPos door = bed.offset(1, 1, 0);
            BlockState lowerDoor = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
            level.setBlock(door, lowerDoor, 3);
            level.setBlock(door.above(), lowerDoor.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
            for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++)
                if ((x == 0 || x == 3) && (z == 0 || z == 3))
                    level.setBlock(bed.offset(x, 2, z), Blocks.SPRUCE_STAIRS.defaultBlockState(), 3);
            BlockPos head = bed.offset(2, 1, 2);
            level.setBlock(bed.offset(1, 1, 2), Blocks.BED.white().defaultBlockState()
                .setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.FOOT), 3);
            level.setBlock(head, Blocks.BED.white().defaultBlockState()
                .setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.HEAD), 3);

            HouseTemplate template = HouseTemplate.capture(level, bed.offset(1, 1, 2), bed);
            require(template != null, "compact small home with a real door and bed is accepted as a village template");
            var steps = template.steps(bed.offset(8, 1, 0), Direction.SOUTH, 100);
            require(steps.stream().anyMatch(s -> s.item() == Blocks.SPRUCE_PLANKS.asItem())
                && steps.stream().anyMatch(s -> s.item() == Blocks.SPRUCE_STAIRS.asItem()),
                "copied home retains its neighborhood palette and roof detail");
            BlockPos target = bed.offset(8, 1, 0);
            for (int x = -2; x <= 5; x++) for (int z = -2; z <= 5; z++)
                level.setBlock(target.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            require(template.supportedFoundation(level, target, Direction.SOUTH, false),
                "level house site has solid support below every foundation block");
            int minY = template.steps(target, Direction.SOUTH, 100).stream().mapToInt(s -> s.pos().getY()).min().orElseThrow();
            var foundationStep = steps.stream().filter(s -> s.pos().getY() == minY).findFirst().orElseThrow();
            level.setBlock(foundationStep.pos().below(), Blocks.AIR.defaultBlockState(), 3);
            require(!template.supportedFoundation(level, target, Direction.SOUTH, false),
                "unsupported foundation edge rejects a floating copied-house site");
            return 4;
        } finally {
            before.forEach((pos, state) -> level.setBlock(pos, state, 3));
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("House template: " + message);
    }
}
