package de.villagerplus.job;

import com.mojang.serialization.JsonOps;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Small deterministic checks for the persisted woodcutting cursor; world/restart checks remain a separate fixture. */
public final class WoodcuttingCycleChecks {
	private WoodcuttingCycleChecks() {}

	public static int run() {
		BlockPos root = new BlockPos(17, 72, -9);
		BlockPos log = root.above(4);
		WoodcutterWork ready = WoodcutterWork.empty().start(root, "minecraft:oak")
			.select(log).withStage(6).claimRemoval(List.of(new ItemStack(Items.OAK_LOG, 1)), Blocks.OAK_LOG);
		var encoded = WoodcutterWork.CODEC.encodeStart(JsonOps.INSTANCE, ready).getOrThrow();
		WoodcutterWork restored = WoodcutterWork.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
		check(restored.root().orElseThrow().equals(root), "tree root survives the persistent codec");
		check(restored.log().orElseThrow().equals(log) && restored.stage() == 9,
			"claimed block and completed crack stage resume without replaying earlier strokes");
		check(restored.removalPending() && restored.pendingDrops().size() == 1
			&& restored.pendingDrops().getFirst().is(Items.OAK_LOG) && restored.pendingBlock().equals("minecraft:oak_log"),
			"the exact drop claim survives while block removal is pending");
		WoodcutterWork complete = restored.afterRemovalDelivered().finishTree();
		check(complete.root().isEmpty() && complete.replantSites().size() == 1
			&& complete.replantSites().getFirst().pos().equals(root)
			&& complete.replantSites().getFirst().species().equals("minecraft:oak"),
			"finished tree queues its own species at the real stump for later replanting");
		check(WoodcutterWork.empty().start(root, "minecraft:oak").select(log).withStage(12).stage() == 9,
			"persisted crack stages are clamped to the visible 0–9 range");
		WoodcutterWork protectedTree = complete.start(root, "minecraft:oak").select(log).withStage(4).abandonTree();
		check(protectedTree.root().isEmpty() && protectedTree.pendingDrops().isEmpty()
			&& protectedTree.replantSites().equals(complete.replantSites()),
			"a newly built feature abandons the active tree without losing unrelated replant work");
		return 6;
	}

	private static void check(boolean value, String message) {
		if (!value) throw new IllegalStateException("Woodcutting cycle: " + message);
	}
}
