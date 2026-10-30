package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.village.VillageRef;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;

/** Checks live recipe lookup and ownership boundaries against a real furnace block entity. */
public final class SmithSmeltingChecks {
	private SmithSmeltingChecks() { }

	public static int run(ServerLevel level, VillageRef ref) {
		BlockPos pos = ref.bell().offset(86, 0, 78);
		var old = level.getBlockState(pos);
		Container store = ref.chest(level);
		ItemStack[] savedStore = new ItemStack[store.getContainerSize()];
		for (int i = 0; i < savedStore.length; i++) savedStore[i] = store.getItem(i).copy();
		int checks = 0;
		try {
			level.getChunkAt(pos);
			level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(pos, Blocks.FURNACE.defaultBlockState(), 3);
			if (!(level.getBlockEntity(pos) instanceof FurnaceBlockEntity furnace)) {
				throw new IllegalStateException("SMITH SMELTING CHECK FAILED: furnace block entity missing");
			}
			furnace.setAttached(VillagerPlus.COMMUNAL_FURNACE, 0);
			furnace.setAttached(VillagerPlus.SMITH_FURNACE, ItemStack.EMPTY);
			var recipe = SmithSmelting.matchingRecipeForCheck(level, new ItemStack(Items.RAW_IRON));
			check(recipe != null && recipe.assemble(new net.minecraft.world.item.crafting.SingleRecipeInput(new ItemStack(Items.RAW_IRON)))
				.is(Items.IRON_INGOT), "the loaded smelting registry resolves raw iron to its current output"); checks++;
			check(recipe != null && SmithSmelting.claimBatch(furnace, new ItemStack(Items.RAW_IRON), recipe)
				&& furnace.getItem(0).is(Items.RAW_IRON)
				&& furnace.getAttached(VillagerPlus.SMITH_FURNACE).is(Items.IRON_INGOT),
				"the expected recipe output is persisted before the real furnace input is exposed"); checks++;
			furnace.setItem(0, ItemStack.EMPTY);
			furnace.setAttached(VillagerPlus.SMITH_FURNACE, ItemStack.EMPTY);
			furnace.setAttached(VillagerPlus.COMMUNAL_FURNACE, 1);
			check(recipe != null && !SmithSmelting.claimBatch(furnace, new ItemStack(Items.RAW_IRON), recipe)
				&& furnace.getItem(0).isEmpty(), "smithing never claims a furnace owned by a pending charcoal batch"); checks++;
			furnace.setAttached(VillagerPlus.COMMUNAL_FURNACE, 0);
			furnace.setItem(0, new ItemStack(Items.COBBLESTONE));
			check(recipe != null && !SmithSmelting.claimBatch(furnace, new ItemStack(Items.RAW_IRON), recipe)
				&& furnace.getItem(0).is(Items.COBBLESTONE), "smithing never overwrites foreign furnace input"); checks++;
			furnace.setItem(0, ItemStack.EMPTY);
			furnace.setAttached(VillagerPlus.COMMUNAL_FURNACE, 0);
			furnace.setAttached(VillagerPlus.SMITH_FURNACE, new ItemStack(Items.IRON_INGOT, 4));
			furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 4));
			for (int i = 0; i < store.getContainerSize(); i++) store.setItem(i, new ItemStack(Items.DIAMOND, 64));
			check(SmithSmelting.deliverClaimedOutput(level, ref, furnace) == 0
				&& furnace.getItem(2).getCount() == 4
				&& furnace.getAttached(VillagerPlus.SMITH_FURNACE).getCount() == 4,
				"full village storage retains both the real furnace output and its ownership claim"); checks++;
			store.setItem(0, new ItemStack(Items.IRON_INGOT, 63));
			check(SmithSmelting.deliverClaimedOutput(level, ref, furnace) == 1
				&& furnace.getItem(2).getCount() == 3
				&& store.getItem(0).getCount() == 64
				&& furnace.getAttached(VillagerPlus.SMITH_FURNACE).getCount() == 4,
				"partial public storage capacity commits only the inserted item and retains the remainder"); checks++;
			store.clearContent();
			check(SmithSmelting.deliverClaimedOutput(level, ref, furnace) == 3
				&& furnace.getItem(2).isEmpty()
				&& furnace.getAttached(VillagerPlus.SMITH_FURNACE).isEmpty()
				&& store.getItem(0).getCount() == 3,
				"remaining output is committed once and releases its persistent furnace claim"); checks++;
			return checks;
		} finally {
			for (int i = 0; i < savedStore.length; i++) store.setItem(i, savedStore[i]);
			store.setChanged();
			level.setBlock(pos, old, 3);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("SMITH SMELTING CHECK FAILED: " + message);
	}
}
