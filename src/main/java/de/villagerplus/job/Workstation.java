package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Berufe holen einen echten Arbeitsblock aus der Gemeinschaftskiste, craften ihn an einer Werkbank,
 * wenn die Zutaten reichen, und stellen ihn selbst auf.
 */
public final class Workstation {
	private Workstation() { }

	public static BlockPos find(net.minecraft.server.level.ServerLevel level, BlockPos origin, Block block, int radius) {
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -2, -radius), origin.offset(radius, 4, radius))) {
			if (level.getBlockState(pos).is(block)) return pos.immutable();
		}
		return null;
	}

	/** @return true while crafting, walking or placing; false when the block is already available or impossible */
	public static boolean ensure(Ctx c, Block block) {
		if (c.village() == null || block == null || block == Blocks.AIR) return false;
		if (find(c.level(), c.village().bell(), block, 8) != null) return false;
		Container chest = communal(c);
		BlockPos chestPos = c.village().chestPos(c.level());
		if (chest == null || chestPos == null) return false;
		Item item = block.asItem();
		if (item == null || item == Items.AIR) return false;
		if (block != Blocks.CRAFTING_TABLE && find(c.level(), c.village().bell(), Blocks.CRAFTING_TABLE, 8) == null) {
			if (ensure(c, Blocks.CRAFTING_TABLE)) return true;
			if (find(c.level(), c.village().bell(), Blocks.CRAFTING_TABLE, 8) == null) return false;
		}
		Predicate<ItemStack> match = stack -> stack.is(item);
		if (JobUtil.count(chest, match) == 0) {
			BlockPos table = find(c.level(), c.village().bell(), Blocks.CRAFTING_TABLE, 8);
			return table != null && RecipeCrafting.craft(c, chest, table, match, 1);
		}
		BlockPos spot = site(c, chestPos);
		if (spot == null) return false;
		c.setActivity("stellt einen Arbeitsblock auf");
		Anim.hold(c, item);
		if (!JobUtil.approach(c, spot, 3.0)) return true;
		if (!c.level().getBlockState(spot).isAir() || JobUtil.take(chest, match, 1) != 1) return false;
		BlockState state = block.defaultBlockState();
		if (state.hasProperty(HorizontalDirectionalBlock.FACING)) {
			state = state.setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH);
		}
		c.level().setBlock(spot, state, 3);
		if (block == Blocks.FURNACE || block == Blocks.SMOKER || block == Blocks.BLAST_FURNACE) {
			var entity = c.level().getBlockEntity(spot);
			if (entity != null) entity.setAttached(VillagerPlus.COMMUNAL_FURNACE, 0);
		}
		c.level().playSound(null, spot, net.minecraft.sounds.SoundEvents.WOOD_PLACE, net.minecraft.sounds.SoundSource.BLOCKS, 0.8F, 1.0F);
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		c.st().cooldownUntil = c.now() + 20;
		return true;
	}

	private static Container communal(Ctx c) {
		BlockPos pos = c.village().chestPos(c.level());
		if (pos == null) return null;
		var entity = c.level().getBlockEntity(pos);
		if (entity != null && entity.getAttached(VillagerPlus.OWNER) != null) return null;
		return c.village().chest(c.level());
	}

	private static BlockPos site(Ctx c, BlockPos chest) {
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos spot = chest.relative(direction, 2);
			BlockPos floor = spot.below();
			if (c.level().getBlockState(spot).isAir() && c.level().getBlockState(spot.above()).isAir()
				&& c.level().getBlockState(floor).isFaceSturdy(c.level(), floor, Direction.UP)) return spot;
		}
		return null;
	}
}
