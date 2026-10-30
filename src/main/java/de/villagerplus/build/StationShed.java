package de.villagerplus.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Lokschuppen im Bahnhof: eine überdachte Schienenzunge mit Antriebsschiene und Redstone,
 * nur auf freien, tragfähigen Bodenflächen.
 */
public final class StationShed {
	private StationShed() { }

	public static int furnish(ServerLevel level, BoundingBox box) {
		int y = box.minY();
		int z = (box.minZ() + box.maxZ()) / 2;
		ListSpan span = span(level, box, y, z);
		if (span == null) return 0;
		int placed = 0;
		int middle = (span.from() + span.to()) / 2;
		for (int x = span.from(); x <= span.to(); x++) {
			BlockPos railPos = new BlockPos(x, y, z);
			boolean powered = x == middle;
			BlockState rail = powered
				? Blocks.POWERED_RAIL.defaultBlockState().setValue(net.minecraft.world.level.block.PoweredRailBlock.SHAPE, RailShape.EAST_WEST)
				: Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST);
			level.setBlock(railPos, rail, 3);
			placed++;
			BlockPos roof = railPos.above(3);
			if (level.getBlockState(roof).isAir()) {
				level.setBlock(roof, Blocks.SPRUCE_SLAB.defaultBlockState(), 3);
				placed++;
			}
		}
		BlockPos power = new BlockPos(middle, y, z + 1);
		if (box.isInside(power) && level.getBlockState(power).isAir()
			&& level.getBlockState(power.below()).isFaceSturdy(level, power.below(), Direction.UP)) {
			level.setBlock(power, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
			placed++;
		}
		BlockPos lamp = new BlockPos(span.from(), y + 2, z);
		if (level.getBlockState(lamp).isAir()) {
			level.setBlock(lamp, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false), 3);
			placed++;
		}
		return placed;
	}

	private static ListSpan span(ServerLevel level, BoundingBox box, int y, int z) {
		int run = 0;
		int start = box.minX();
		for (int x = box.minX(); x <= box.maxX(); x++) {
			BlockPos pos = new BlockPos(x, y, z);
			BlockPos floor = pos.below();
			boolean open = level.getBlockState(pos).isAir() && level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP);
			if (open) {
				if (run == 0) start = x;
				run++;
				if (run >= 5) return new ListSpan(x - 4, x);
			} else run = 0;
		}
		return null;
	}

	private record ListSpan(int from, int to) { }
}
