package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.village.VillageRef;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;

/** Focused ownership and conservation checks for village-owned furnace batches. */
public final class CharcoalChecks {
	private CharcoalChecks() {}

	public static int run(ServerLevel level, VillageRef ref) {
		BlockPos pos = ref.bell().offset(82, 0, 78);
		var old = level.getBlockState(pos);
		int checks = 0;
		try {
			level.getChunkAt(pos);
			level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(pos, Blocks.FURNACE.defaultBlockState(), 3);
			if (!(level.getBlockEntity(pos) instanceof FurnaceBlockEntity furnace)) {
				throw new IllegalStateException("CHARCOAL CHECK FAILED: furnace block entity missing");
			}
			furnace.setAttached(VillagerPlus.COMMUNAL_FURNACE, 0);
			SimpleContainer storage = new SimpleContainer(2);
			furnace.setItem(0, new ItemStack(Items.IRON_INGOT));
			check(!CharcoalJob.claimLog(furnace, new ItemStack(Items.OAK_LOG)) && furnace.getItem(0).is(Items.IRON_INGOT),
				"foreign furnace input cannot be claimed or overwritten"); checks++;
			furnace.setItem(0, ItemStack.EMPTY);
			furnace.setAttached(VillagerPlus.COMMUNAL_FURNACE, 0);
			check(CharcoalJob.claimLog(furnace, new ItemStack(Items.OAK_LOG))
				&& furnace.getItem(0).is(Items.OAK_LOG)
				&& furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) == 1,
				"a marked furnace claims exactly one real log before smelting"); checks++;
			furnace.setItem(0, ItemStack.EMPTY);
			furnace.setItem(2, new ItemStack(Items.CHARCOAL, 3));
			storage.setItem(0, new ItemStack(Items.CHARCOAL, 64));
			storage.setItem(1, new ItemStack(Items.CHARCOAL, 64));
			check(CharcoalJob.deliverClaimedOutput(level, ref, furnace, storage) == 0
				&& furnace.getItem(2).getCount() == 3
				&& furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) == 1,
				"full public storage leaves charcoal output and its batch claim untouched"); checks++;
			storage.clearContent();
			check(CharcoalJob.deliverClaimedOutput(level, ref, furnace, storage) == 1
				&& furnace.getItem(2).getCount() == 2
				&& storage.getItem(0).getCount() == 1
				&& furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) == 0,
				"only the marked one-log batch transfers and excess output stays in the furnace"); checks++;
			return checks;
		} finally {
			level.setBlock(pos, old, 3);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("CHARCOAL CHECK FAILED: " + message);
	}
}
