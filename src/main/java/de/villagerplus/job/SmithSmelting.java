package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.village.Balance;
import de.villagerplus.village.Lager;
import de.villagerplus.village.Specials;
import de.villagerplus.village.StockPolicy;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Real, recipe-registry-driven cooking and smelting in marked village furnaces. */
final class SmithSmelting {
	private static final long SCAN_INTERVAL = 1200L;
	private enum Worker { SMITH, COOK }

	private SmithSmelting() { }

	static boolean work(Ctx c) {
		return work(c, Worker.SMITH);
	}

	static boolean cook(Ctx c) {
		return work(c, Worker.COOK);
	}

	private static boolean work(Ctx c, Worker worker) {
		if (c.village() == null || !Specials.has(c.village(), Blueprints.KITCHEN)) return false;
		BlockPos kitchen = Specials.get(c.village(), Blueprints.KITCHEN);
		if (kitchen == null || !c.level().hasChunkAt(kitchen)) return false;
		BlockPos pos = c.st().target;
		AbstractFurnaceBlockEntity furnace = pos == null ? null : furnaceAt(c.level(), pos, worker);
		if (furnace == null || !insideKitchen(pos, kitchen)) {
			if (c.now() < c.st().buildCheckAt) return false;
			c.st().buildCheckAt = c.now() + SCAN_INTERVAL;
			pos = findFurnace(c.level(), kitchen, worker);
			furnace = pos == null ? null : furnaceAt(c.level(), pos, worker);
			if (furnace == null) return false;
			c.st().target = pos;
		}

		ItemStack claim = furnace.getAttached(VillagerPlus.SMITH_FURNACE);
		if (claim == null) return false;
		if (claim.isEmpty()) {
			if (furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) != 0
				|| !furnace.getItem(0).isEmpty() || !furnace.getItem(2).isEmpty()) return false;
			Batch batch = findBatch(c.level(), c.village(), furnace, worker);
			if (batch == null || !JobUtil.approach(c, pos, 3.0)) return batch != null;
			ItemStack input = batch.source().removeItem(batch.slot(), 1);
			if (input.isEmpty() || !furnace.canPlaceItem(0, input)) {
				if (!input.isEmpty()) HopperBlockEntity.addItem(null, batch.source(), input, null);
				return false;
			}
			if (!claimBatch(furnace, input, batch.recipe(), worker)) {
				HopperBlockEntity.addItem(null, batch.source(), input, null);
				return false;
			}
			c.level().sendBlockUpdated(pos, c.level().getBlockState(pos), c.level().getBlockState(pos), 3);
			c.setActivity(worker == Worker.COOK ? "beschickt den Räucherofen nach Rezept" : "beschickt den Dorfofen nach Rezept");
			Anim.hold(c, input.getItem());
			JobUtil.swing(c.v());
			c.mem().jobsDone++;
			c.st().cooldownUntil = c.now() + 80;
			return true;
		}

		if (!JobUtil.approach(c, pos, 3.0)) return true;
		ItemStack output = furnace.getItem(2);
		if (!output.isEmpty()) {
			int moved = deliverClaimedOutput(c.level(), c.village(), furnace);
			if (moved <= 0) return false;
			c.level().sendBlockUpdated(pos, c.level().getBlockState(pos), c.level().getBlockState(pos), 3);
			c.setActivity(worker == Worker.COOK ? "lagert geräucherte Mahlzeit ein" : "lagert Schmiedeerzeugnis ein");
			Anim.hold(c, claim.getItem());
			JobUtil.swing(c.v());
			c.mem().jobsDone++;
			c.st().cooldownUntil = c.now() + 80;
			return true;
		}

		ItemStack input = furnace.getItem(0);
		if (input.isEmpty()) {
			// An interrupted/removed batch cannot keep the public furnace locked forever.
			furnace.setAttached(VillagerPlus.SMITH_FURNACE, ItemStack.EMPTY);
			furnace.setChanged();
			return false;
		}
		AbstractCookingRecipe recipe = matchingRecipe(c.level(), input, recipeType(c.level().getBlockState(pos)));
		if (recipe == null || !ItemStack.isSameItemSameComponents(recipe.assemble(new SingleRecipeInput(input)), claim)) return false;
		if (furnace.getItem(1).isEmpty()) {
			Container storage = Lager.combined(c.level(), c.village());
			ItemStack fuel = storage == null ? ItemStack.EMPTY : withdraw(storage, s -> s.is(ItemTags.COALS));
			if (fuel.isEmpty()) return false;
			furnace.setItem(1, fuel);
		}
		if (!furnace.getItem(1).is(ItemTags.COALS)) return false;
		furnace.setChanged();
		c.level().sendBlockUpdated(pos, c.level().getBlockState(pos), c.level().getBlockState(pos), 3);
		c.setActivity(worker == Worker.COOK ? "räuchert eine Mahlzeit nach Rezept" : "schmilzt nach geladenem Rezept");
		Anim.hold(c, input.getItem());
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		c.st().cooldownUntil = c.now() + 100;
		return true;
	}

	private static Batch findBatch(ServerLevel level, de.villagerplus.village.VillageRef ref, AbstractFurnaceBlockEntity furnace, Worker worker) {
		List<Container> stores = worker == Worker.COOK
			? Specials.containers(level, ref, Blueprints.GRANARY) : Balance.storage(level, ref);
		if (stores.isEmpty()) return null;
		RecipeType<? extends AbstractCookingRecipe> type = recipeType(level.getBlockState(furnace.getBlockPos()));
		if (type == null) return null;
		for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof AbstractCookingRecipe recipe) || recipe.getType() != type) continue;
			for (Container store : stores) for (int slot = 0; slot < store.getContainerSize(); slot++) {
				ItemStack stack = store.getItem(slot);
				if (stack.isEmpty() || !recipe.input().test(stack)) continue;
				ItemStack one = stack.copyWithCount(1);
				if (!recipe.matches(new SingleRecipeInput(one), level)) continue;
				if (validOutput(recipe.assemble(new SingleRecipeInput(one)), worker)) return new Batch(store, slot, recipe);
			}
		}
		return null;
	}

	private static AbstractCookingRecipe matchingRecipe(ServerLevel level, ItemStack input, RecipeType<? extends AbstractCookingRecipe> type) {
		SingleRecipeInput single = new SingleRecipeInput(input);
		for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
			if (holder.value() instanceof AbstractCookingRecipe recipe && recipe.getType() == type && recipe.matches(single, level)) return recipe;
		}
		return null;
	}

	static boolean claimBatch(AbstractFurnaceBlockEntity furnace, ItemStack input, AbstractCookingRecipe recipe) {
		return claimBatch(furnace, input, recipe, Worker.SMITH);
	}

	private static boolean claimBatch(AbstractFurnaceBlockEntity furnace, ItemStack input, AbstractCookingRecipe recipe, Worker worker) {
		if (furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) == null
			|| furnace.getAttached(VillagerPlus.SMITH_FURNACE) == null
			|| furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) != 0
			|| !furnace.getAttached(VillagerPlus.SMITH_FURNACE).isEmpty()
			|| !furnace.getItem(0).isEmpty() || !furnace.getItem(2).isEmpty()
			|| input.isEmpty() || input.getCount() != 1 || !furnace.canPlaceItem(0, input)) return false;
		ItemStack expected = recipe.assemble(new SingleRecipeInput(input));
		if (!validOutput(expected, worker)) return false;
		// Persist ownership before exposing the input to vanilla furnace ticking.
		furnace.setAttached(VillagerPlus.SMITH_FURNACE, expected.copy());
		furnace.setItem(0, input.copy());
		furnace.setChanged();
		return true;
	}

	static int deliverClaimedOutput(ServerLevel level, de.villagerplus.village.VillageRef ref, AbstractFurnaceBlockEntity furnace) {
		ItemStack claim = furnace.getAttached(VillagerPlus.SMITH_FURNACE);
		ItemStack output = furnace.getItem(2);
		if (claim == null || claim.isEmpty() || output.isEmpty() || !ItemStack.isSameItemSameComponents(output, claim)) return 0;
		int before = output.getCount();
		ItemStack remainder = StockPolicy.insertVillage(level, ref, output.copy());
		int moved = before - remainder.getCount();
		if (moved <= 0) return 0;
		output.shrink(moved);
		furnace.setItem(2, output);
		if (output.isEmpty()) furnace.setAttached(VillagerPlus.SMITH_FURNACE, ItemStack.EMPTY);
		furnace.setChanged();
		return moved;
	}

	static SmeltingRecipe matchingRecipeForCheck(ServerLevel level, ItemStack input) {
		AbstractCookingRecipe recipe = matchingRecipe(level, input, RecipeType.SMELTING);
		return recipe instanceof SmeltingRecipe smelting ? smelting : null;
	}

	private static boolean validOutput(ItemStack output, Worker worker) {
		if (output.isEmpty()) return false;
		if (worker == Worker.COOK) return Meals.isPreparedFood(output);
		StockPolicy.Category category = StockPolicy.category(output.getItem());
		return category == StockPolicy.Category.MATERIAL || category == StockPolicy.Category.TOOLS;
	}

	private static ItemStack withdraw(Container store, Predicate<ItemStack> match) {
		for (int slot = 0; slot < store.getContainerSize(); slot++) {
			ItemStack stack = store.getItem(slot);
			if (!stack.isEmpty() && match.test(stack)) return store.removeItem(slot, 1);
		}
		return ItemStack.EMPTY;
	}

	private static AbstractFurnaceBlockEntity furnaceAt(ServerLevel level, BlockPos pos, Worker worker) {
		if (!level.hasChunkAt(pos)) return null;
		BlockState state = level.getBlockState(pos);
		if (recipeType(state) == null || worker == Worker.COOK && !state.is(Blocks.SMOKER)
			|| worker == Worker.SMITH && state.is(Blocks.SMOKER)) return null;
		if (!(level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)
			|| furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) == null
			|| furnace.getAttached(VillagerPlus.SMITH_FURNACE) == null) return null;
		Integer pending = furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE);
		return worker == Worker.SMITH && pending != 0 ? null : furnace;
	}

	private static BlockPos findFurnace(ServerLevel level, BlockPos center, Worker worker) {
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-16, -4, -16), center.offset(16, 8, 16))) {
			AbstractFurnaceBlockEntity furnace = furnaceAt(level, pos, worker);
			if (furnace == null) continue;
			double distance = pos.distSqr(center);
			if (distance < bestDistance) { best = pos.immutable(); bestDistance = distance; }
		}
		return best;
	}

	private static boolean insideKitchen(BlockPos furnace, BlockPos origin) {
		return Math.abs(furnace.getX() - origin.getX()) <= 16 && Math.abs(furnace.getZ() - origin.getZ()) <= 16
			&& furnace.getY() >= origin.getY() - 4 && furnace.getY() <= origin.getY() + 16;
	}

	private static RecipeType<? extends AbstractCookingRecipe> recipeType(BlockState state) {
		if (state.is(Blocks.FURNACE)) return RecipeType.SMELTING;
		if (state.is(Blocks.BLAST_FURNACE)) return RecipeType.BLASTING;
		if (state.is(Blocks.SMOKER)) return RecipeType.SMOKING;
		return null;
	}

	private record Batch(Container source, int slot, AbstractCookingRecipe recipe) { }
}
