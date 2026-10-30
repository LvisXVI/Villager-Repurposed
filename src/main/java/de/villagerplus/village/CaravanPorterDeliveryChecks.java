package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.dev.Checks090;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** K04: communal caravan cargo routes to the granary instead of the material chest. */
public final class CaravanPorterDeliveryChecks {
	private CaravanPorterDeliveryChecks() { }

	public static int run(ServerLevel level, BlockPos at) {
		level.getChunkAt(at);
		int groundY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()) - 1;
		at = new BlockPos(at.getX(), groundY + 1, at.getZ());
		var ref = Checks090.fixtureForEquipment(level, at);
		BlockPos granary = ref.bell().north(18);
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
			BlockPos p = granary.offset(dx, 0, dz);
			level.setBlock(p.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(p, dx == 0 && dz == 0 ? Blocks.CHEST.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			level.setBlock(p.above(), Blocks.AIR.defaultBlockState(), 3);
		}
		Specials.set(ref, Blueprints.GRANARY, granary);
		int checks = 0;
		try {
			ItemStack wheat = new ItemStack(Items.WHEAT, 16);
			check(StockPolicy.deliveryRoom(level, ref, Items.WHEAT) > 0, "village reserves granary capacity for food deliveries"); checks++;
			check(Specials.depotWithRoom(level, ref, Blueprints.GRANARY, wheat) != null, "granary accepts caravan wheat before porter pickup"); checks++;
			check(Caravan.porterStand(level, granary) != null, "granary exposes a safe adjacent porter stand"); checks++;
			return checks;
		} finally {
			level.setBlock(granary, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(granary.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
		}
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("Caravan porter delivery: " + message);
		System.out.println("CARAVAN DELIVERY PASS " + message);
	}
}
