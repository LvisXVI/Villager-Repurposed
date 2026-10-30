package de.villagerplus.job;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;

/** Applies a loaded server crafting recipe to chest contents as a single inventory transaction. */
final class RecipeCrafting {
	private RecipeCrafting() { }

	static boolean craft(Ctx c, Container chest, Predicate<ItemStack> outputFilter, int outputCap) {
		return craft(c, chest, JobUtil.chest(c), outputFilter, outputCap);
	}

	static boolean craft(Ctx c, Container chest, BlockPos workPos, Predicate<ItemStack> outputFilter, int outputCap) {
		for (RecipeHolder<?> holder : c.level().recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof CraftingRecipe recipe)) {
				continue;
			}
			ItemStack preview = preview(recipe);
			if (preview != null && (preview.isEmpty() || !outputFilter.test(preview) || count(chest, outputFilter) >= outputCap)) {
				continue;
			}
			CraftingInput input = inputFor(recipe, chest, c.level());
			if (input == null) {
				continue;
			}
			ItemStack output = recipe.assemble(input);
			if (output.isEmpty() || !outputFilter.test(output) || count(chest, outputFilter) >= outputCap) {
				continue;
			}
			List<ItemStack> remainder = recipe.getRemainingItems(input);
			if (workPos != null && !JobUtil.approach(c, workPos, 3.0)) return true;
			if (commit(chest, input, output, remainder)) {
				c.setActivity("stellt her: " + output.getHoverName().getString());
				Anim.hold(c, output.getItem());
				c.st().cooldownUntil = c.now() + 100;
				c.mem().jobsDone++;
				return true;
			}
		}
		return false;
	}

	/** Vanilla's concrete shaped/shapeless implementations return their fixed result even for an empty input. */
	static ItemStack preview(CraftingRecipe recipe) {
		if (recipe.getClass() == ShapedRecipe.class || recipe.getClass() == ShapelessRecipe.class) {
			return recipe.assemble(CraftingInput.EMPTY);
		}
		return null;
	}

	/**
	 * Uses the public recipe placement contract rather than class-specific ingredient accessors. A PlacementInfo maps each
	 * crafting slot to an ingredient index (or EMPTY_SLOT); because that contract carries no width/height, try each legal
	 * rectangle whose area matches the slot map and let the recipe itself select its accepted geometry.
	 */
	static CraftingInput inputFor(CraftingRecipe recipe, Container chest, Level level) {
		PlacementInfo placement = recipe.placementInfo();
		List<Ingredient> required = placement.ingredients();
		var slotMap = placement.slotsToIngredientIndex();
		if (placement.isImpossibleToPlace() || required.isEmpty() || required.size() > 9 || slotMap.isEmpty() || slotMap.size() > 9) return null;
		List<ItemStack> chosen = assign(chest, required);
		if (chosen == null) return null;
		boolean[] used = new boolean[required.size()];
		for (int i = 0; i < slotMap.size(); i++) {
			int ingredient = slotMap.getInt(i);
			if (ingredient == PlacementInfo.EMPTY_SLOT) continue;
			if (ingredient < 0 || ingredient >= required.size() || used[ingredient]) return null;
			used[ingredient] = true;
		}
		for (boolean found : used) if (!found) return null;

		for (int width = 1; width <= 3; width++) {
			if (slotMap.size() % width != 0) continue;
			int height = slotMap.size() / width;
			if (height < 1 || height > 3) continue;
			List<ItemStack> grid = new ArrayList<>(java.util.Collections.nCopies(slotMap.size(), ItemStack.EMPTY));
			for (int slot = 0; slot < slotMap.size(); slot++) {
				int ingredient = slotMap.getInt(slot);
				if (ingredient != PlacementInfo.EMPTY_SLOT) grid.set(slot, chosen.get(ingredient));
			}
			CraftingInput input = CraftingInput.of(width, height, grid);
			if (recipe.matches(input, level)) return input;
		}
		return null;
	}

	private static List<ItemStack> assign(Container chest, List<Ingredient> ingredients) {
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < ingredients.size(); i++) order.add(i);
		order.sort(java.util.Comparator.comparingInt(index -> matchingStacks(chest, ingredients.get(index))));
		List<ItemStack> chosen = new ArrayList<>(java.util.Collections.nCopies(ingredients.size(), ItemStack.EMPTY));
		int[] reserved = new int[chest.getContainerSize()];
		return assignAt(chest, ingredients, order, 0, reserved, chosen) ? chosen : null;
	}

	private static int matchingStacks(Container chest, Ingredient ingredient) {
		int count = 0;
		for (int i = 0; i < chest.getContainerSize(); i++) if (!chest.getItem(i).isEmpty() && ingredient.test(chest.getItem(i))) count++;
		return count;
	}

	private static boolean assignAt(Container chest, List<Ingredient> ingredients, List<Integer> order,
			int depth, int[] reserved, List<ItemStack> chosen) {
		if (depth == order.size()) return true;
		int index = order.get(depth);
		Ingredient ingredient = ingredients.get(index);
		for (int slot = 0; slot < chest.getContainerSize(); slot++) {
			ItemStack stack = chest.getItem(slot);
			if (stack.isEmpty() || !ingredient.test(stack) || stack.getCount() <= reserved[slot]) continue;
			reserved[slot]++;
			chosen.set(index, stack.copyWithCount(1));
			if (assignAt(chest, ingredients, order, depth + 1, reserved, chosen)) return true;
			reserved[slot]--;
		}
		return false;
	}

	private static int count(Container chest, Predicate<ItemStack> filter) {
		int count = 0;
		for (int slot = 0; slot < chest.getContainerSize(); slot++) {
			ItemStack existing = chest.getItem(slot);
			if (!existing.isEmpty() && filter.test(existing)) count += existing.getCount();
		}
		return count;
	}

	private static boolean commit(Container chest, CraftingInput input, ItemStack output, List<ItemStack> remainders) {
		List<ItemStack> simulated = new ArrayList<>(chest.getContainerSize());
		for (int i = 0; i < chest.getContainerSize(); i++) simulated.add(chest.getItem(i).copy());
		for (ItemStack ingredient : input.items()) {
			if (ingredient.isEmpty()) continue;
			int left = 1;
			for (int i = 0; i < simulated.size() && left > 0; i++) {
				ItemStack stack = simulated.get(i);
				if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, ingredient)) {
					stack.shrink(left);
					if (stack.isEmpty()) simulated.set(i, ItemStack.EMPTY);
					left = 0;
				}
			}
			if (left > 0) return false;
		}
		for (ItemStack remaining : remainders) if (!remaining.isEmpty() && !insert(simulated, chest, remaining.copy())) return false;
		if (!insert(simulated, chest, output.copy())) return false;
		for (int i = 0; i < simulated.size(); i++) chest.setItem(i, simulated.get(i));
		chest.setChanged();
		return true;
	}

	private static boolean insert(List<ItemStack> slots, Container chest, ItemStack moving) {
		for (int i = 0; i < slots.size() && !moving.isEmpty(); i++) {
			ItemStack existing = slots.get(i);
			if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, moving) && chest.canPlaceItem(i, moving)) {
				int room = Math.min(existing.getMaxStackSize(), chest.getMaxStackSize()) - existing.getCount();
				int count = Math.min(room, moving.getCount());
				if (count > 0) { existing.grow(count); moving.shrink(count); }
			}
		}
		for (int i = 0; i < slots.size() && !moving.isEmpty(); i++) {
			if (slots.get(i).isEmpty() && chest.canPlaceItem(i, moving)) {
				int count = Math.min(moving.getCount(), Math.min(moving.getMaxStackSize(), chest.getMaxStackSize()));
				slots.set(i, moving.copyWithCount(count));
				moving.shrink(count);
			}
		}
		return moving.isEmpty();
	}
}
