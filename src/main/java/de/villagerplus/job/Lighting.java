package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.village.VillageRef;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Laternenanzünder: sucht Blocklicht unter 1 im Dorf und auf den Ausfallachsen der Überlandrouten
 * und setzt eine echte Fackel oder Laterne aus der Gemeinschaftskiste.
 */
public final class Lighting {
	private Lighting() { }

	public static BlockPos findDark(ServerLevel level, BlockPos origin, int radius) {
		BlockPos near = scanDisk(level, origin, radius);
		if (near != null) return near;
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			for (int step = radius + 8; step <= radius + 40; step += 8) {
				BlockPos far = surface(level, origin.relative(direction, step));
				if (far != null && placeable(level, far)) return far;
			}
		}
		return null;
	}

	public static boolean placeable(ServerLevel level, BlockPos air) {
		if (air == null || !level.hasChunkAt(air)) return false;
		BlockState here = level.getBlockState(air);
		BlockPos below = air.below();
		BlockState floor = level.getBlockState(below);
		if (!here.isAir() || !here.getFluidState().isEmpty() || !floor.getFluidState().isEmpty()) return false;
		if (!floor.isFaceSturdy(level, below, Direction.UP) || floor.is(Blocks.FARMLAND) || floor.hasBlockEntity()) return false;
		return level.getBrightness(LightLayer.BLOCK, air) < 1;
	}

	/** @return true while the worker is fetching, walking or placing */
	public static boolean work(Ctx c) {
		VillageRef ref = c.village();
		if (ref == null || c.now() < c.st().lightAt) return false;
		BlockPos spot = c.st().target != null && placeable(c.level(), c.st().target) ? c.st().target : findDark(c.level(), ref.bell(), 12);
		if (spot == null) {
			c.st().lightAt = c.now() + 200;
			c.st().clearTarget();
			return false;
		}
		Container chest = communal(c);
		if (chest == null) return false;
		boolean torch = JobUtil.count(chest, stack -> stack.is(Items.TORCH)) > 0;
		boolean lantern = JobUtil.count(chest, stack -> stack.is(Items.LANTERN)) > 0;
		if (!torch && !lantern) {
			c.st().lightAt = c.now() + 200;
			c.st().clearTarget();
			return false;
		}
		c.st().target = spot.immutable();
		var item = torch ? Items.TORCH : Items.LANTERN;
		c.setActivity(torch ? "stellt eine Fackel auf" : "setzt eine Laterne");
		Anim.hold(c, item);
		if (!JobUtil.approach(c, spot, 3.0)) return true;
		if (!placeable(c.level(), spot) || JobUtil.take(chest, stack -> stack.is(item), 1) != 1) {
			c.st().clearTarget();
			return false;
		}
		BlockState state = torch ? Blocks.TORCH.defaultBlockState()
			: Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false);
		c.level().setBlock(spot, state, 3);
		c.level().playSound(null, spot, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.7F, 1.0F);
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		c.st().lightAt = c.now() + 20;
		c.st().clearTarget();
		return true;
	}

	private static BlockPos scanDisk(ServerLevel level, BlockPos origin, int radius) {
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (dx * dx + dz * dz > radius * radius) continue;
				BlockPos spot = surface(level, origin.offset(dx, 0, dz));
				if (spot != null && placeable(level, spot)) return spot;
			}
		}
		return null;
	}

	private static BlockPos surface(ServerLevel level, BlockPos column) {
		if (!level.hasChunkAt(column)) return null;
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
		return new BlockPos(column.getX(), y, column.getZ());
	}

	private static Container communal(Ctx c) {
		BlockPos pos = c.village().chestPos(c.level());
		if (pos == null) return null;
		var blockEntity = c.level().getBlockEntity(pos);
		if (blockEntity != null && blockEntity.getAttached(VillagerPlus.OWNER) != null) return null;
		return c.village().chest(c.level());
	}
}
