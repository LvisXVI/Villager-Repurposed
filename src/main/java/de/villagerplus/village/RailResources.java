package de.villagerplus.village;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Material ledger for placing one rail segment from a village's real stores. */
final class RailResources {
	private RailResources() { }

	static boolean canPay(ServerLevel level, VillageRef village, boolean powered, boolean needsSupport) {
		List<Container> stores = stores(level, village);
		return has(stores, powered ? Items.POWERED_RAIL : Items.RAIL, 1)
			&& (!needsSupport || has(stores, Items.REDSTONE_BLOCK, 1));
	}

	/** Minimum locally available ironwork inputs required to unlock a bilateral rail project. */
	static boolean hasMetallurgyStock(ServerLevel level, VillageRef village) {
		List<Container> stores = stores(level, village);
		return has(stores, Items.IRON_INGOT, 8)
			&& has(stores, Items.GOLD_INGOT, 8)
			&& has(stores, Items.REDSTONE, 16)
			&& has(stores, Items.RAIL, 8);
	}

	static boolean pay(ServerLevel level, VillageRef village, boolean powered, boolean needsSupport) {
		List<Container> stores = stores(level, village);
		if (!has(stores, powered ? Items.POWERED_RAIL : Items.RAIL, 1)
			|| (needsSupport && !has(stores, Items.REDSTONE_BLOCK, 1))) return false;
		remove(stores, powered ? Items.POWERED_RAIL : Items.RAIL, 1);
		if (needsSupport) remove(stores, Items.REDSTONE_BLOCK, 1);
		return true;
	}

	private static List<Container> stores(ServerLevel level, VillageRef village) {
		Set<Container> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		List<Container> stores = new ArrayList<>();
		for (Container box : Balance.storage(level, village)) if (box != null && seen.add(box)) stores.add(box);
		return stores;
	}

	private static boolean has(List<Container> stores, Item item, int amount) {
		int count = 0;
		for (Container box : stores) for (int slot = 0; slot < box.getContainerSize(); slot++) {
			ItemStack stack = box.getItem(slot);
			if (!stack.isEmpty() && stack.is(item)) {
				count += stack.getCount();
				if (count >= amount) return true;
			}
		}
		return false;
	}

	private static void remove(List<Container> stores, Item item, int amount) {
		int left = amount;
		for (Container box : stores) for (int slot = 0; slot < box.getContainerSize() && left > 0; slot++) {
			ItemStack stack = box.getItem(slot);
			if (stack.isEmpty() || !stack.is(item)) continue;
			int take = Math.min(stack.getCount(), left);
			stack.shrink(take);
			if (stack.isEmpty()) box.setItem(slot, ItemStack.EMPTY);
			box.setChanged();
			left -= take;
		}
	}
}
