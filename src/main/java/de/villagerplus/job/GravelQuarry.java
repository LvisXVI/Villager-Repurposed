package de.villagerplus.job;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Steinmetz bricht freiliegenden Schotter für Straßen und Bahn, lässt verbaute Fahrbahn stehen. */
public final class GravelQuarry {
	private GravelQuarry() { }

	public static boolean roadSurface(ServerLevel level, BlockPos gravel) {
		int cobble = 0;
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			if (level.getBlockState(gravel.relative(direction)).is(Blocks.COBBLESTONE)
				|| level.getBlockState(gravel.relative(direction)).is(Blocks.SANDSTONE)) cobble++;
		}
		return cobble >= 2;
	}

	public static BlockPos find(ServerLevel level, BlockPos origin, int radius) {
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -4, -radius), origin.offset(radius, 4, radius))) {
			if (!level.getBlockState(pos).is(Blocks.GRAVEL) || roadSurface(level, pos)) continue;
			if (level.getBlockState(pos.above()).isAir()) return pos.immutable();
		}
		return null;
	}

	public static boolean work(Ctx c) {
		if (c.village() == null) return false;
		ContainerCount stock = new ContainerCount(c);
		if (stock.gravel >= 32) return false;
		BlockPos pos = c.st().target;
		if (pos == null || !c.level().getBlockState(pos).is(Blocks.GRAVEL) || roadSurface(c.level(), pos)) {
			pos = find(c.level(), c.v().blockPosition(), 12);
			c.st().target = pos;
		}
		if (pos == null) return false;
		c.setActivity("bricht Schotter");
		if (!JobUtil.approach(c, pos, 3.2)) return true;
		BlockState state = c.level().getBlockState(pos);
		if (!state.is(Blocks.GRAVEL)) {
			c.st().target = null;
			return false;
		}
		if (!Anim.breakStep(c, pos, state, Items.IRON_SHOVEL)) return true;
		List<ItemStack> drops = Block.getDrops(state, c.level(), pos, null, c.v(), new ItemStack(Items.IRON_SHOVEL));
		for (ItemStack drop : drops) JobUtil.give(c.level(), c.v(), drop);
		Anim.blockBreak(c.level(), pos, state);
		c.level().setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		c.st().target = null;
		c.mem().jobsDone++;
		c.st().cooldownUntil = c.now() + 10;
		return true;
	}

	private record ContainerCount(int gravel) {
		ContainerCount(Ctx c) {
			this(c.village().chest(c.level()) == null ? 0 : JobUtil.count(c.village().chest(c.level()), stack -> stack.is(Items.GRAVEL)));
		}
	}
}
