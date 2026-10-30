package de.villagerplus.job;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.NormalCraftingRecipe;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/** Development assertions for custom CraftingRecipe implementations using only the public placement contract. */
public final class RecipeCraftingChecks {
	private RecipeCraftingChecks() { }

	public static int run(ServerLevel level) {
		int passed = 0;
		SimpleContainer chest = new SimpleContainer(9);
		chest.setItem(0, new ItemStack(Items.BIRCH_PLANKS));
		chest.setItem(1, new ItemStack(Items.STICK));
		chest.setItem(2, new ItemStack(Items.STICK));

		Ingredient anyPlank = Ingredient.of(BuiltInRegistries.ITEM.getOrThrow(ItemTags.PLANKS));
		List<Optional<Ingredient>> pattern = List.of(Optional.of(anyPlank), Optional.empty(),
			Optional.of(Ingredient.of(Items.STICK)), Optional.of(Ingredient.of(Items.STICK)));
		CustomRecipe customShaped = new CustomRecipe(pattern, 2, 2, Items.CRAFTING_TABLE);
		CraftingInput shaped = RecipeCrafting.inputFor(customShaped, chest, level);
		check(shaped != null && shaped.width() == 2 && shaped.height() == 2
			&& shaped.getItem(0).is(Items.BIRCH_PLANKS) && shaped.getItem(1).isEmpty()
			&& shaped.getItem(2).is(Items.STICK) && shaped.getItem(3).is(Items.STICK),
			"non-vanilla shaped recipe placement preserves holes and resolves a registered item tag"); passed++;

		chest.setItem(2, ItemStack.EMPTY);
		check(RecipeCrafting.inputFor(customShaped, chest, level) == null,
			"generic shaped recipe rejects missing ingredients without constructing a partial grid"); passed++;

		chest.clearContent();
		chest.setItem(0, new ItemStack(Items.BIRCH_PLANKS));
		chest.setItem(1, new ItemStack(Items.STICK));
		CustomRecipe customShapeless = CustomRecipe.shapeless(List.of(anyPlank, Ingredient.of(Items.STICK)), Items.PAPER);
		CraftingInput shapeless = RecipeCrafting.inputFor(customShapeless, chest, level);
		check(shapeless != null && shapeless.ingredientCount() == 2
			&& (shapeless.getItem(0).is(Items.BIRCH_PLANKS) || shapeless.getItem(1).is(Items.BIRCH_PLANKS)),
			"non-vanilla shapeless recipe uses placement indices and item tags"); passed++;
		return passed;
	}

	private static final class CustomRecipe extends NormalCraftingRecipe {
		private final PlacementInfo placement;
		private final List<Optional<Ingredient>> slots;
		private final int width;
		private final int height;
		private final net.minecraft.world.item.Item result;
		private final boolean shapeless;

		private CustomRecipe(List<Optional<Ingredient>> slots, int width, int height, net.minecraft.world.item.Item result) {
			super(new net.minecraft.world.item.crafting.Recipe.CommonInfo(true),
				new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, ""));
			this.slots = List.copyOf(slots);
			this.placement = PlacementInfo.createFromOptionals(slots);
			this.width = width;
			this.height = height;
			this.result = result;
			this.shapeless = false;
		}

		private CustomRecipe(List<Ingredient> ingredients, net.minecraft.world.item.Item result) {
			super(new net.minecraft.world.item.crafting.Recipe.CommonInfo(true),
				new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, ""));
			this.slots = List.of();
			this.placement = PlacementInfo.create(ingredients);
			this.width = 0;
			this.height = 0;
			this.result = result;
			this.shapeless = true;
		}

		private static CustomRecipe shapeless(List<Ingredient> ingredients, net.minecraft.world.item.Item result) {
			return new CustomRecipe(ingredients, result);
		}

		@Override protected PlacementInfo createPlacementInfo() { return placement; }
		@Override public boolean matches(CraftingInput input, Level level) {
			if (shapeless) {
				if (input.ingredientCount() != placement.ingredients().size()) return false;
				boolean[] used = new boolean[input.size()];
				for (Ingredient ingredient : placement.ingredients()) {
					boolean found = false;
					for (int i = 0; i < input.size(); i++) if (!used[i] && ingredient.test(input.getItem(i))) {
						used[i] = true;
						found = true;
						break;
					}
					if (!found) return false;
				}
				return true;
			}
			if (input.width() != width || input.height() != height || input.size() != slots.size()) return false;
			for (int i = 0; i < slots.size(); i++) {
				if (!Ingredient.testOptionalIngredient(slots.get(i), input.getItem(i))) return false;
			}
			return true;
		}
		@Override public ItemStack assemble(CraftingInput input) { return new ItemStack(result); }
		@Override public RecipeSerializer<? extends NormalCraftingRecipe> getSerializer() { return null; }
	}

	private static void check(boolean condition, String label) {
		if (!condition) throw new IllegalStateException("RECIPE CRAFTING CHECK FAILED: " + label);
		System.out.println("RECIPE CRAFTING PASS " + label);
	}
}
