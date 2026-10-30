package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.dev.Checks090;
import de.villagerplus.job.JobUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** G08/D06–D08: metropolis gates, bilateral track construction, and chest-minecart freight on a real corridor. */
public final class RailEndToEndChecks {
	private RailEndToEndChecks() { }

	public static int run(ServerLevel level, BlockPos at) {
		level.getChunkAt(at);
		int groundY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()) - 1;
		at = new BlockPos(at.getX(), groundY + 1, at.getZ());
		level.setBlock(at.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(at.above(), Blocks.AIR.defaultBlockState(), 3);
		var ref = Checks090.fixtureForEquipment(level, at);
		BlockPos peerAt = new BlockPos(at.getX() + 256, at.getY(), at.getZ());
		level.getChunkAt(peerAt);
		level.setBlock(peerAt.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(peerAt.above(), Blocks.AIR.defaultBlockState(), 3);
		var peer = Checks090.fixtureForEquipment(level, peerAt);
		Container homeBox = ref.chest(level);
		Container peerBox = peer.chest(level);
		var oldA = Network.of(ref.bellEntity());
		var oldB = Network.of(peer.bellEntity());
		List<Villager> fixtures = new ArrayList<>();
		int checks = 0;
		try {
			linkMetropolis(level, ref, peer);
			List<Villager> peerResidents = spawnResidents(level, peer, fixtures);
			Villages.resetAssignmentCache();
			List<Villager> homeResidents = spawnResidents(level, ref, fixtures);
			for (int i = 0; i < homeResidents.size(); i++) {
				Villager v = homeResidents.get(i);
				v.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), ref.bell()));
			}
			for (int i = 0; i < peerResidents.size(); i++) {
				Villager v = peerResidents.get(i);
				v.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), peer.bell()));
			}
			check(peerResidents.stream().filter(Villager::isAlive).count() == Roads.RAIL_POP,
				"all peer fixtures stay alive after spawn"); checks++;
			for (Villager v : homeResidents) level.getChunkAt(v.blockPosition());
			for (Villager v : peerResidents) level.getChunkAt(v.blockPosition());
			level.tick(() -> false);
			check(homeResidents.stream().filter(v -> v.getBrain().getMemory(MemoryModuleType.MEETING_POINT)
				.filter(g -> g.pos().equals(ref.bell())).isPresent()).count() == Roads.RAIL_POP,
				"home fixtures remember the village bell meeting point"); checks++;
			check(Villages.residents(level, ref, de.villagerplus.Config.VILLAGE_RADIUS, v -> true).size() == Roads.RAIL_POP,
				"resident census counts every bound home villager (" + Roads.RAIL_POP + ")"); checks++;
			int peerCensus = Villages.residents(level, peer, de.villagerplus.Config.VILLAGE_RADIUS, v -> true).size();
			check(peerCensus == Roads.RAIL_POP,
				"peer census counts every bound peer villager (" + Roads.RAIL_POP + ")"); checks++;
			DepotLayout home = layoutDepots(level, ref, homeBox);
			DepotLayout peerLayout = layoutDepots(level, peer, peerBox);
			stockMetallurgy(homeBox);
			stockMetallurgy(peerBox);
			stockRailsInStation(level, home.station());
			stockRailsInStation(level, peerLayout.station());
			addRails(homeBox, 48);
			addRails(peerBox, 48);
			check(Roads.railReady(level, ref) && Roads.railReady(level, peer),
				"both endpoints pass railReady with milestones, population and metallurgy"); checks++;
			String routeKey = Network.dim(level) + peer.bell().asLong();
			String peerKey = Network.dim(level) + ref.bell().asLong();
			Map<String, Long> roads = new HashMap<>();
			Map<String, Long> peerRoads = new HashMap<>();
			Roads.syncRouteGeometry(level, ref.bell(), peer.bell(), roads, routeKey, peerRoads, peerKey);
			ref.bellEntity().setAttached(VillagerPlus.ROADS, roads);
			peer.bellEntity().setAttached(VillagerPlus.ROADS, peerRoads);
			int length = RoadRoute.length(roads, routeKey, ref.bell(), peer.bell());
			paveCorridor(level, roads, routeKey, ref.bell(), peer.bell(), length);
			BlockPos station = Specials.get(ref, Blueprints.STATION);
			int stationStart = Roads.stationRailStartForCheck(level, ref.bell(), peer.bell(), station, roads, routeKey);
			check(stationStart >= 0, "station boarding cursor resolves on the paved corridor (got " + stationStart + ")"); checks++;
			check(RoadRoute.ensureWithRetry(level, ref.bell(), peer.bell(), roads, routeKey, peerRoads, peerKey, level.getGameTime(), false),
				"shared route geometry is committed before bilateral rail advances"); checks++;
			BlockPos routeAtStation = RoadRoute.point(roads, routeKey, ref.bell(), peer.bell(), stationStart);
			BlockPos railBed = railCell(level, routeAtStation.getX(), routeAtStation.getZ(), ref.bell().getY());
			check(railBed != null && level.getBlockState(railBed).isAir(),
				"station route cell exposes an empty rail bed before construction"); checks++;
			check(de.villagerplus.village.RailResources.hasMetallurgyStock(level, ref)
				&& de.villagerplus.village.RailResources.canPay(level, ref, false, false),
				"village stores still cover metallurgy and a plain rail segment after paving"); checks++;
			for (int attempt = 0; attempt < 128; attempt++) {
				roads = new HashMap<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS, Map.of()));
				roads.put("next", 0L);
				ref.bellEntity().setAttached(VillagerPlus.ROADS, roads);
				Roads.tick(level, ref, level.getGameTime() + 100L + attempt);
				peerRoads = new HashMap<>(peer.bellEntity().getAttachedOrElse(VillagerPlus.ROADS, Map.of()));
				peerRoads.put("next", 0L);
				peer.bellEntity().setAttached(VillagerPlus.ROADS, peerRoads);
				Roads.tick(level, peer, level.getGameTime() + 100L + attempt);
			}
			roads = ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS, Map.of());
			long cursor = roads.getOrDefault("rail" + peer.bell().asLong(), 0L);
			int rails = countRailsOnRoute(level, roads, routeKey, ref.bell(), peer.bell(), length);
			check(cursor > 0 && rails >= 3,
				"metropolis ticks advance the rail cursor (" + cursor + ") and place at least three connected segments (found " + rails + ")"); checks++;
			BlockPos startRail = firstRailOnRoute(level, roads, routeKey, ref.bell(), peer.bell(), length);
			check(startRail != null, "corridor exposes a rail cell for freight"); checks++;
			MinecartChest cart = EntityTypes.CHEST_MINECART.create(level, EntitySpawnReason.COMMAND);
			cart.setPos(startRail.getX() + 0.5, startRail.getY() + 0.5, startRail.getZ() + 0.5);
			cart.setNoGravity(true);
			check(level.addFreshEntity(cart), "chest minecart loads on the built track"); checks++;
			cart.setItem(0, new ItemStack(Items.IRON_INGOT, 32));
			Container depot = ref.chest(level);
			int before = JobUtil.count(depot, s -> s.is(Items.IRON_INGOT));
			var trip = new RailTransport.Trip(ref.bell(), peer.bell(), startRail, startRail, true, false, true, level.getGameTime(), 0);
			cart.setAttached(RailTransport.TRIP, trip);
			RailTransport.transfer(cart, 0, depot, 16);
			check(JobUtil.count(depot, s -> s.is(Items.IRON_INGOT)) == before + 16 && cart.getItem(0).getCount() == 16,
				"freight unloads from the physical chest minecart into communal storage"); checks++;
			return checks;
		} finally {
			fixtures.forEach(v -> {
				v.discard();
				Villages.forget(v.getUUID());
			});
			Network.save(ref.bellEntity(), oldA);
			Network.save(peer.bellEntity(), oldB);
		}
	}

	private record DepotLayout(BlockPos station, BlockPos granary, BlockPos toolshed) {
		BoundingBox shedBox() {
			int y = station.getY() - 1;
			return new BoundingBox(station.getX() - 4, y, station.getZ() - 1, station.getX() + 4, y + 5, station.getZ() + 1);
		}
	}

	private static void linkMetropolis(ServerLevel level, VillageRef ref, VillageRef peer) {
		Network.save(ref.bellEntity(), new TradeNetwork(List.of(), List.of(
			new TradeNetwork.Link(Network.dim(level), peer.bell(), "Metro peer", Roads.METROPOLIS, true, 0, 0)), Map.of(), 0, 0));
		Network.save(peer.bellEntity(), new TradeNetwork(List.of(), List.of(
			new TradeNetwork.Link(Network.dim(level), ref.bell(), "Metro home", Roads.METROPOLIS, true, 0, 0)), Map.of(), 0, 0));
		String approval = Network.pairKey(ref, peer, "large_project_approval");
		Network.save(ref.bellEntity(), Network.of(ref.bellEntity()).withCooldown(approval, 1L));
		Network.save(peer.bellEntity(), Network.of(peer.bellEntity()).withCooldown(approval, 1L));
	}

	private static List<Villager> spawnResidents(ServerLevel level, VillageRef ref, List<Villager> fixtures) {
		List<Villager> out = new ArrayList<>();
		for (int i = 0; i < Roads.RAIL_POP; i++) {
			BlockPos spawn = ref.bell().offset(2 + i, 0, 3 + (i % 3));
			level.getChunkAt(spawn);
			level.setBlock(spawn, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(spawn.above(), Blocks.AIR.defaultBlockState(), 3);
			Villager villager = EntityTypes.VILLAGER.spawn(level, spawn, EntitySpawnReason.COMMAND);
			if (villager == null) throw new IllegalStateException("resident fixture did not spawn");
			villager.setPos(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
			villager.addTag("vp090_fixture");
			villager.setInvulnerable(true);
			villager.setNoAi(true);
			villager.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), ref.bell()));
			villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), ref.bell().north(4 + i)));
			level.getChunkAt(ref.bell());
			Villages.noteChunk(level.getChunkAt(ref.bell()));
			Villages.find(level, villager);
			fixtures.add(villager);
			out.add(villager);
		}
		return out;
	}

	private static DepotLayout layoutDepots(ServerLevel level, VillageRef ref, Container stock) {
		BlockPos granary = ref.bell().north(16);
		BlockPos toolshed = ref.bell().north(24);
		BlockPos station = ref.bell().east(10);
		preparePad(level, granary);
		preparePad(level, toolshed);
		preparePad(level, station);
		registerChest(level, ref, Blueprints.GRANARY, granary, granary.east(2));
		registerChest(level, ref, Blueprints.TOOLSHED, toolshed, toolshed.east(2));
		registerChest(level, ref, Blueprints.STATION, station, station.east(2));
		stockMetallurgy(stock);
		return new DepotLayout(station, granary, toolshed);
	}

	private static void preparePad(ServerLevel level, BlockPos origin) {
		level.getChunkAt(origin);
		for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
			BlockPos p = origin.offset(dx, 0, dz);
			level.setBlock(p.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(p, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			level.setBlock(p.above(), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(p.above(2), Blocks.AIR.defaultBlockState(), 3);
		}
	}

	private static void registerChest(ServerLevel level, VillageRef ref, String type, BlockPos origin, BlockPos chestPos) {
		Specials.set(ref, type, origin);
		level.setBlock(chestPos.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
	}

	private static void stockMetallurgy(Container box) {
		box.clearContent();
		box.setItem(0, new ItemStack(Items.IRON_INGOT, 16));
		box.setItem(1, new ItemStack(Items.GOLD_INGOT, 16));
		box.setItem(2, new ItemStack(Items.REDSTONE, 32));
	}

	private static void stockRailsInStation(ServerLevel level, BlockPos stationOrigin) {
		BlockPos chestPos = stationOrigin.east(2);
		var box = net.minecraft.world.level.block.entity.HopperBlockEntity.getContainerAt(level, chestPos);
		if (box == null) throw new IllegalStateException("station chest missing at " + chestPos);
		addRails(box, 48);
	}

	private static void addRails(Container box, int count) {
		for (int slot = 0; slot < box.getContainerSize() && count > 0; slot++) {
			ItemStack existing = box.getItem(slot);
			if (!existing.isEmpty() && !existing.is(Items.RAIL)) continue;
			int room = existing.isEmpty() ? 64 : existing.getMaxStackSize() - existing.getCount();
			if (room <= 0) continue;
			int stack = Math.min(room, count);
			if (existing.isEmpty()) box.setItem(slot, new ItemStack(Items.RAIL, stack));
			else existing.grow(stack);
			count -= stack;
		}
		if (count > 0) throw new IllegalStateException("material chest has no room for rail stock (size=" + box.getContainerSize() + ")");
	}

	private static void paveCorridor(ServerLevel level, Map<String, Long> roads, String routeKey, BlockPos a, BlockPos b, int length) {
		int floorY = a.getY() - 1;
		for (int cursor = 0; cursor <= length / 2 + 1; cursor++) {
			BlockPos route = RoadRoute.point(roads, routeKey, a, b, cursor);
			boolean alongX = RoadRoute.alongX(roads, routeKey, a, b, cursor);
			for (int side = -1; side <= 1; side++) {
				int x = route.getX() + (alongX ? 0 : side);
				int z = route.getZ() + (alongX ? side : 0);
				BlockPos floor = new BlockPos(x, floorY, z);
				if (Roads.isProtectedLaneCell(level, floor)) continue;
				level.getChunkAt(floor);
				level.setBlock(floor.below(), Blocks.STONE.defaultBlockState(), 3);
				level.setBlock(floor, (side == 0 ? Blocks.GRAVEL : Blocks.COBBLESTONE).defaultBlockState(), 3);
				level.setBlock(floor.above(), Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(floor.above(2), Blocks.AIR.defaultBlockState(), 3);
			}
		}
	}

	private static int countRailsOnRoute(ServerLevel level, Map<String, Long> roads, String routeKey, BlockPos a, BlockPos b, int length) {
		int rails = 0;
		for (int cursor = 0; cursor <= length / 2 + 1; cursor++) {
			BlockPos route = RoadRoute.point(roads, routeKey, a, b, cursor);
			BlockPos rail = railCell(level, route.getX(), route.getZ(), a.getY());
			if (rail != null && level.getBlockState(rail).getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock) rails++;
		}
		return rails;
	}

	private static BlockPos firstRailOnRoute(ServerLevel level, Map<String, Long> roads, String routeKey, BlockPos a, BlockPos b, int length) {
		for (int cursor = 0; cursor <= length / 2 + 1; cursor++) {
			BlockPos route = RoadRoute.point(roads, routeKey, a, b, cursor);
			BlockPos rail = railCell(level, route.getX(), route.getZ(), a.getY());
			if (rail != null && level.getBlockState(rail).getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock) return rail;
		}
		return null;
	}

	private static BlockPos railCell(ServerLevel level, int x, int z, int platformY) {
		int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		int high = Math.max(top, platformY + 1);
		int low = Math.min(top - 3, platformY - 4);
		for (int y = high; y >= low; y--) {
			BlockPos pos = new BlockPos(x, y, z);
			net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
			if (state.getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock) return pos;
			if (railRoad(state)) return pos.above();
		}
		return null;
	}

	private static boolean railRoad(net.minecraft.world.level.block.state.BlockState state) {
		return state.is(Blocks.GRAVEL) || state.is(Blocks.SMOOTH_SANDSTONE) || state.is(Blocks.SPRUCE_PLANKS);
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("Rail end-to-end: " + message);
		System.out.println("RAIL E2E PASS " + message);
	}
}
