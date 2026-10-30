package de.villagerplus.dev;

import de.villagerplus.VillagerPlus;
import de.villagerplus.village.VillageRef;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;

/** Focused dev checks for the charcoal furnace claim. Run only in the remote CTOV test area. */
public final class RoleChecks {
	private RoleChecks() { }

	private static Object invoke(Class<?> owner, String name, Class<?>[] signature, Object... args) throws Exception {
		Method method = owner.getDeclaredMethod(name, signature);
		method.setAccessible(true);
		return method.invoke(null, args);
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("ROLE CHECK FAILED: " + message);
	}

	public static int run(ServerLevel level, BlockPos origin, VillageRef ref) throws Exception {
		if (!java.util.Set.of("ctov", "vp-master-test-2026-10-29", "vp-master-test-2026-10-30", "vp-k04-fresh-2026-10-30", "vp-k04-fresh2-2026-10-30", "vp-k04-fresh3-2026-10-30", "vp-k04-fresh4-2026-10-30", "vp-k04-fresh5-2026-10-30", "vp-k04-fresh6-2026-10-30", "vp-k04-fresh7-2026-10-30", "vp-k04-fresh8-2026-10-30", "vp-k04-fresh9-2026-10-30", "vp-k04-fresh10-2026-10-30","vp-k04-fresh11-2026-10-30","vp-k04-fresh12-2026-10-30","vp-k04-fresh13-2026-10-30","vp-k04-fresh14-2026-10-30", "vp-k04-fresh15-2026-10-30", "vp-k04-fresh16-2026-10-30", "vp-k04-fresh17-2026-10-30", "vp-k04-fresh18-2026-10-30","vp-k04-fresh19-2026-10-30","vp-k04-fresh20-2026-10-30").contains(level.getServer().getWorldData().getLevelName())) throw new IllegalStateException("Role checks only allowed in explicitly named development worlds");
		BlockPos unmarkedPos = origin.immutable();
		BlockPos claimedPos = origin.east(3);
		BlockPos pendingPos = origin.east(6);
		for (BlockPos pos : new BlockPos[] {unmarkedPos, claimedPos, pendingPos}) {
			level.getChunk(pos);
			level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(pos, Blocks.FURNACE.defaultBlockState(), 3);
		}
		int passed = 0;
		try {
			FurnaceBlockEntity unmarked = (FurnaceBlockEntity) level.getBlockEntity(unmarkedPos);
			unmarked.setItem(2, new ItemStack(Items.CHARCOAL, 2));
			check(invoke(de.villagerplus.job.CharcoalJob.class, "furnaceAt", new Class[] {ServerLevel.class, BlockPos.class}, level, unmarkedPos) == null,
				"unmarked furnace rejected");
			check(unmarked.getItem(2).getCount() == 2, "unmarked furnace output remains untouched");
			passed += 2;

			FurnaceBlockEntity claimed = (FurnaceBlockEntity) level.getBlockEntity(claimedPos);
			claimed.setAttached(VillagerPlus.COMMUNAL_FURNACE, 1);
			claimed.setItem(2, new ItemStack(Items.CHARCOAL, 3));
			SimpleContainer publicStorage = new SimpleContainer(4);
			int moved = (Integer) invoke(de.villagerplus.job.CharcoalJob.class, "deliverClaimedOutput",
				new Class[] {ServerLevel.class, VillageRef.class, FurnaceBlockEntity.class, net.minecraft.world.Container.class}, level, ref, claimed, publicStorage);
			check(moved == 1 && publicStorage.getItem(0).is(Items.CHARCOAL) && publicStorage.getItem(0).getCount() == 1,
				"pending batch transfers only its claimed output count");
			check(claimed.getItem(2).getCount() == 2 && claimed.getAttached(VillagerPlus.COMMUNAL_FURNACE) == 0,
				"excess output stays in furnace after claim is consumed");
			passed += 2;

			FurnaceBlockEntity pending = (FurnaceBlockEntity) level.getBlockEntity(pendingPos);
			pending.setAttached(VillagerPlus.COMMUNAL_FURNACE, 0);
			boolean claimedLog = (Boolean) invoke(de.villagerplus.job.CharcoalJob.class, "claimLog",
				new Class[] {FurnaceBlockEntity.class, ItemStack.class}, pending, new ItemStack(Items.OAK_LOG));
			check(claimedLog && pending.getAttached(VillagerPlus.COMMUNAL_FURNACE) == 1 && pending.getItem(0).is(Items.OAK_LOG),
				"one-log output claim is persisted before furnace input is set");
			var saved = pending.saveWithFullMetadata(level.registryAccess());
			BlockEntity restored = BlockEntity.loadStatic(pendingPos, level.getBlockState(pendingPos), saved, level.registryAccess());
			check(restored.getAttached(VillagerPlus.COMMUNAL_FURNACE) == 1 && ((FurnaceBlockEntity) restored).getItem(0).is(Items.OAK_LOG),
				"pending charcoal claim survives block-entity save/load");
			passed += 2;
			return passed;
		} finally {
			for (BlockPos pos : new BlockPos[] {unmarkedPos, claimedPos, pendingPos}) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(pos.below(), Blocks.AIR.defaultBlockState(), 3);
			}
		}
	}
}

