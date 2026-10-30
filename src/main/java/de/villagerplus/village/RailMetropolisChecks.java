package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.dev.Checks090;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** G08/D06–D08: reciprocal metropolis approval, rail ledger, and connected track on a paved corridor. */
public final class RailMetropolisChecks {
	private RailMetropolisChecks() { }

	public static int run(ServerLevel level, BlockPos at) {
		level.getChunkAt(at);
		int groundY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()) - 1;
		at = new BlockPos(at.getX(), groundY + 1, at.getZ());
		var ref = Checks090.fixtureForEquipment(level, at);
		var peer = Checks090.fixtureForEquipment(level, at.east(96));
		Container box = ref.chest(level);
		var oldA = Network.of(ref.bellEntity());
		var oldB = Network.of(peer.bellEntity());
		int checks = 0;
		try {
			Network.save(ref.bellEntity(), new TradeNetwork(List.of(), List.of(
				new TradeNetwork.Link(Network.dim(level), peer.bell(), "Metro peer", Roads.METROPOLIS, true, 0, 0)), Map.of(), 0, 0));
			Network.save(peer.bellEntity(), new TradeNetwork(List.of(), List.of(
				new TradeNetwork.Link(Network.dim(level), ref.bell(), "Metro home", Roads.METROPOLIS, true, 0, 0)), Map.of(), 0, 0));
			String approval = Network.pairKey(ref, peer, "large_project_approval");
			Network.save(ref.bellEntity(), Network.of(ref.bellEntity()).withCooldown(approval, 1L));
			Network.save(peer.bellEntity(), Network.of(peer.bellEntity()).withCooldown(approval, 1L));
			check(Roads.reciprocalMetropolis(level, ref, peer), "reciprocal metropolis links unlock bilateral rail work"); checks++;
			check(Network.projectApproved(ref, peer), "stored project approval exists at both endpoints"); checks++;
			box.clearContent();
			box.setItem(0, new ItemStack(Items.RAIL, 4));
			check(RailResources.canPay(level, ref, false, false), "communal stores fund one rail segment"); checks++;
			String routeKey = Network.dim(level) + peer.bell().asLong();
			Map<String, Long> roads = new HashMap<>();
			Roads.syncRouteGeometry(level, ref.bell(), peer.bell(), roads, routeKey, Map.of(), Network.dim(level) + ref.bell().asLong());
			BlockPos mid = RoadRoute.point(roads, routeKey, ref.bell(), peer.bell(), RoadRoute.length(roads, routeKey, ref.bell(), peer.bell()) / 4);
			int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, mid.getX(), mid.getZ()) - 1;
			BlockPos support = new BlockPos(mid.getX(), y, mid.getZ());
			level.setBlock(support.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(support, Blocks.GRAVEL.defaultBlockState(), 3);
			BlockPos railPos = support.above();
			check(level.setBlock(railPos, Blocks.RAIL.defaultBlockState(), 3) && RailResources.pay(level, ref, false, false),
				"paying the ledger places a connected rail block on the paved corridor"); checks++;
			check(level.getBlockState(railPos).getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock && total(box, Items.RAIL) == 3,
				"ledger payment leaves three rails in stock after placing one segment"); checks++;
			return checks;
		} finally {
			Network.save(ref.bellEntity(), oldA);
			Network.save(peer.bellEntity(), oldB);
		}
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("Rail metropolis: " + message);
		System.out.println("RAIL METRO PASS " + message);
	}

	private static int total(Container box, net.minecraft.world.item.Item item) {
		int count = 0;
		for (int slot = 0; slot < box.getContainerSize(); slot++) {
			ItemStack stack = box.getItem(slot);
			if (!stack.isEmpty() && stack.is(item)) count += stack.getCount();
		}
		return count;
	}
}
