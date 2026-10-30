package de.villagerplus.job;

import de.villagerplus.build.Blueprints;
import de.villagerplus.village.Balance;
import de.villagerplus.village.Lager;
import de.villagerplus.village.Network;
import de.villagerplus.village.Roads;
import de.villagerplus.village.Specials;
import de.villagerplus.village.VillageRef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/** Crafts rails, powered rails, redstone blocks and freight carts when a village feeds its rail network. */
final class SmithRailCraft {
	private static final List<Item> TARGETS = List.of(Items.RAIL, Items.POWERED_RAIL, Items.REDSTONE_BLOCK, Items.CHEST_MINECART);

	private SmithRailCraft() { }

	static boolean work(Ctx c) {
		VillageRef village = c.village();
		if (village == null || !Specials.has(village, Blueprints.TOOLSHED) || !Specials.has(village, Blueprints.STATION)) {
			return false;
		}
		if (!railWorkshopActive(c)) {
			return false;
		}
		Item target = firstShortage(c.level(), village);
		if (target == null) {
			return false;
		}
		Container inv = c.v().getInventory();
		ItemStack carried = firstOf(inv, target);
		if (!carried.isEmpty()) {
			Anim.hold(c, carried.getItem());
			BlockPos drop = JobUtil.communalDeliveryChest(c);
			if (drop == null) {
				return false;
			}
			if (!JobUtil.approachLong(c, drop, 3.0)) {
				return true;
			}
			boolean moved = false;
			for (int slot = 0; slot < inv.getContainerSize(); slot++) {
				ItemStack stack = inv.getItem(slot);
				if (stack.isEmpty() || !stack.is(target)) {
					continue;
				}
				ItemStack rest = de.villagerplus.village.StockPolicy.insertVillage(c.level(), village, stack);
				if (rest.getCount() != stack.getCount()) {
					moved = true;
				}
				inv.setItem(slot, rest);
			}
			inv.setChanged();
			if (moved) {
				JobUtil.swing(c.v());
				c.st().cooldownUntil = c.now() + 100;
				c.mem().jobsDone++;
				return true;
			}
			return false;
		}
		BlockPos bench = bench(c);
		if (bench == null) {
			return false;
		}
		List<BlockPos> sources = Lager.materialChests(c.level(), village);
		Container available = inv;
		for (BlockPos pos : sources) {
			Container source = HopperBlockEntity.getContainerAt(c.level(), pos);
			if (source != null) {
				available = new CompoundContainer(available, source);
			}
		}
		for (var holder : c.level().recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof net.minecraft.world.item.crafting.CraftingRecipe recipe)) {
				continue;
			}
			ItemStack preview = RecipeCrafting.preview(recipe);
			if (preview == null || preview.isEmpty() || !preview.is(target)) {
				continue;
			}
			net.minecraft.world.item.crafting.CraftingInput input = RecipeCrafting.inputFor(recipe, available, c.level());
			if (input == null) {
				continue;
			}
			ItemStack output = recipe.assemble(input);
			if (output.isEmpty() || !output.is(target) || stock(c.level(), village, target) >= reserve(target)) {
				continue;
			}
			for (ItemStack ingredient : input.items()) {
				if (ingredient.isEmpty()) {
					continue;
				}
				int required = 0;
				for (ItemStack part : input.items()) {
					if (ItemStack.isSameItemSameComponents(part, ingredient)) {
						required += part.getCount();
					}
				}
				int own = JobUtil.count(inv, stack -> ItemStack.isSameItemSameComponents(stack, ingredient));
				if (own >= required) {
					continue;
				}
				for (BlockPos pos : sources) {
					Container source = HopperBlockEntity.getContainerAt(c.level(), pos);
					if (source == null) {
						continue;
					}
					for (int slot = 0; slot < source.getContainerSize(); slot++) {
						ItemStack stack = source.getItem(slot);
						if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, ingredient)) {
							continue;
						}
						c.setActivity("holt Schienenmaterial");
						Anim.hold(c, stack.getItem());
						if (!JobUtil.approachLong(c, pos, 3.0)) {
							return true;
						}
						ItemStack moving = stack.copyWithCount(Math.min(required - own, stack.getCount()));
						int before = moving.getCount();
						ItemStack rest = HopperBlockEntity.addItem(null, inv, moving, null);
						int moved = before - rest.getCount();
						if (moved == 0) {
							return false;
						}
						stack.shrink(moved);
						source.setChanged();
						inv.setChanged();
						JobUtil.swing(c.v());
						c.st().cooldownUntil = c.now() + 40;
						return true;
					}
				}
				return false;
			}
			c.setActivity("fertigt Bahnmaterial an der Werkbank");
			if (!JobUtil.approachLong(c, bench, 2.8)) {
				return true;
			}
			return RecipeCrafting.craft(c, inv, bench, stack -> stack.is(target), reserve(target));
		}
		return false;
	}

	private static boolean railWorkshopActive(Ctx c) {
		VillageRef village = c.village();
		if (village == null) {
			return false;
		}
		boolean metropolisRail = Network.of(village.bellEntity()).links().stream()
			.anyMatch(link -> link.relation() >= Roads.METROPOLIS && link.route() && link.dimension().equals(Network.dim(c.level())));
		if (metropolisRail && hasMetallurgyStock(c.level(), village)) {
			return true;
		}
		return metropolisRail && firstShortage(c.level(), village) != null;
	}

	private static boolean hasMetallurgyStock(net.minecraft.server.level.ServerLevel level, VillageRef village) {
		return stock(level, village, Items.IRON_INGOT) >= 8
			&& stock(level, village, Items.GOLD_INGOT) >= 8
			&& stock(level, village, Items.REDSTONE) >= 16;
	}

	private static Item firstShortage(net.minecraft.server.level.ServerLevel level, VillageRef village) {
		for (Item item : TARGETS) {
			if (stock(level, village, item) < reserve(item)) {
				return item;
			}
		}
		return null;
	}

	private static int reserve(Item item) {
		if (item == Items.RAIL) {
			return 16;
		}
		if (item == Items.POWERED_RAIL) {
			return 8;
		}
		if (item == Items.REDSTONE_BLOCK) {
			return 4;
		}
		if (item == Items.CHEST_MINECART) {
			return 2;
		}
		return 0;
	}

	private static int stock(net.minecraft.server.level.ServerLevel level, VillageRef village, Item item) {
		int count = 0;
		for (Container box : stores(level, village)) {
			for (int slot = 0; slot < box.getContainerSize(); slot++) {
				ItemStack stack = box.getItem(slot);
				if (!stack.isEmpty() && stack.is(item)) {
					count += stack.getCount();
				}
			}
		}
		return count;
	}

	private static List<Container> stores(net.minecraft.server.level.ServerLevel level, VillageRef village) {
		Set<Container> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		List<Container> stores = new ArrayList<>();
		for (Container box : Balance.storage(level, village)) {
			if (box != null && seen.add(box)) {
				stores.add(box);
			}
		}
		return stores;
	}

	private static ItemStack firstOf(Container inv, Item item) {
		for (int slot = 0; slot < inv.getContainerSize(); slot++) {
			ItemStack stack = inv.getItem(slot);
			if (!stack.isEmpty() && stack.is(item)) {
				return stack;
			}
		}
		return ItemStack.EMPTY;
	}

	private static BlockPos bench(Ctx c) {
		BlockPos origin = Specials.get(c.village(), Blueprints.TOOLSHED);
		if (origin == null) {
			return null;
		}
		BlockPos cached = c.st().toolCraftBench;
		if (cached != null && Math.abs(cached.getX() - origin.getX()) <= 8 && Math.abs(cached.getZ() - origin.getZ()) <= 8
			&& c.level().hasChunkAt(cached) && c.level().getBlockState(cached).is(Blocks.CRAFTING_TABLE)) {
			return cached;
		}
		if (c.now() < c.st().toolCraftScanAt) {
			return null;
		}
		c.st().toolCraftScanAt = c.now() + 200;
		c.st().toolCraftBench = null;
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-8, -2, -8), origin.offset(8, 6, 8))) {
			if (c.level().hasChunkAt(pos) && c.level().getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
				c.st().toolCraftBench = pos.immutable();
				return c.st().toolCraftBench;
			}
		}
		return null;
	}
}
