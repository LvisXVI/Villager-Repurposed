package de.villagerplus.job;

import de.villagerplus.Config;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/** Cook/Chef: uses food ingredients already stored in the village pantry to make meals. */
public final class CookJob implements Job {
	@Override
	public Predicate<ItemStack> produce() {
		return Meals::isFood;
	}

	@Override
	public int keep() {
		return 0;
	}

	@Override
	public boolean primary(Ctx c) {
		if (c.idle(0)) return false;
		if (JobUtil.depositSurplus(c, produce(), Config.FARM_DEPOSIT_THRESHOLD, keep())) return true;
		if (cookFromGranary(c)) return true;
		c.setIdle(0, 200);
		return false;
	}

	@Override
	public boolean secondary(Ctx c) {
		if (c.idle(1)) return false;
		if (cookFromGranary(c)) return true;
		c.setIdle(1, 400);
		return false;
	}

	@Override
	public boolean indoorsDuringStorm() { return true; }

	private static boolean cookFromGranary(Ctx c) {
		if (c.village() == null) return false;
		if (Workstation.ensure(c, net.minecraft.world.level.block.Blocks.FURNACE)) return true;
		var pot = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(
			net.minecraft.resources.Identifier.parse("farmersdelight:cooking_pot")).orElse(null);
		if (pot != null && Workstation.ensure(c, pot)) return true;
		var board = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(
			net.minecraft.resources.Identifier.parse("farmersdelight:cutting_board")).orElse(null);
		if (board != null && Workstation.ensure(c, board)) return true;
		if (SmithSmelting.cook(c)) return true;
		BlockPos origin = de.villagerplus.village.Specials.get(c.village(), "granary");
		if (origin == null || !c.level().hasChunkAt(origin)) return false;
		ServerLevel level = c.level();
		List<BlockPos> seen = new ArrayList<>();
		for (BlockPos p : BlockPos.betweenClosed(origin.offset(-9, -2, -9), origin.offset(9, 8, 9))) {
			var state = level.getBlockState(p);
			boolean chest = state.getBlock() instanceof ChestBlock
				&& state.getValue(ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.RIGHT;
			if (!chest && !(state.getBlock() instanceof BarrelBlock)) continue;
			BlockPos pos = p.immutable();
			if (seen.contains(pos)) continue;
			seen.add(pos);
			Container box = HopperBlockEntity.getContainerAt(level, pos);
			if (box == null) continue;
			if (RecipeCrafting.craft(c, box, pos, CookJob::preparedMeal, 24)) return true;
		}
		return false;
	}

	private static boolean preparedMeal(ItemStack stack) {
		return Meals.isPreparedFood(stack);
	}
}
