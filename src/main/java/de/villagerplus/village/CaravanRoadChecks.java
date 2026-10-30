package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

/** Isolated world-backed regression checks for caravan navigation over completed roads. */
public final class CaravanRoadChecks {
	private CaravanRoadChecks() { }

	/**
	 * Caller must supply an already loaded, otherwise unused flat test area centered on {@code at}.
	 * The fixture does not load chunks; it snapshots and restores every block/entity position it edits.
	 */
	public static int run(ServerLevel level, BlockPos at) throws Exception {
		BlockPos source = at.immutable();
		BlockPos east = source.east(40);
		BlockPos south = source.south(40);
		BlockPos remote = source.east(400);
		Set<BlockPos> touched = fixturePositions(source, east, south);
		for (BlockPos pos : touched) {
			if (!level.hasChunkAt(pos)) throw new IllegalStateException("Caravan road check requires preloaded fixture chunks: " + pos);
		}
		if (level.hasChunkAt(remote)) throw new IllegalStateException("Caravan road check remote fallback coordinate must be unloaded: " + remote);

		Map<BlockPos, Cell> before = snapshot(level, touched);
		BlockEntity sourceEntityBefore = level.getBlockEntity(source);
		// Keep the typed attachment values as a second restoration guard alongside the BE metadata.
		Object oldSourceNetwork = attached(sourceEntityBefore, VillagerPlus.NETWORK);
		Object oldSourceRoads = attached(sourceEntityBefore, VillagerPlus.ROADS);
		Object oldSourceVillage = attached(sourceEntityBefore, VillagerPlus.VILLAGE);
		int checks = 0;
		WanderingTrader dummy = null;
		try {
			prepareGround(level, source, east, south);
			VillageRef a = placeVillage(level, source);
			VillageRef b = placeVillage(level, east);
			VillageRef c = placeVillage(level, south);
			String dimension = Network.dim(level);
			TradeNetwork.Link ab = new TradeNetwork.Link(dimension, east, "Ost", 90, true, 0, 0);
			TradeNetwork.Link ac = new TradeNetwork.Link(dimension, south, "Süd", 90, true, 0, 0);
			Network.save(a.bellEntity(), TradeNetwork.create(source).withLinks(List.of(ab, ac)));
			Network.save(b.bellEntity(), TradeNetwork.create(east));
			Network.save(c.bellEntity(), TradeNetwork.create(south));

			Map<String, Long> roadState = new HashMap<>();
			String eastKey = dimension + east.asLong();
			String southKey = dimension + south.asLong();
			check(RoadRoute.ensure(level, source, east, roadState, eastKey, null, null), "east canonical route plans"); checks++;
			check(RoadRoute.ensure(level, source, south, roadState, southKey, null, null), "south canonical route plans"); checks++;
			pave(level, roadState, source, east, eastKey);
			pave(level, roadState, source, south, southKey);
			a.bellEntity().setAttached(VillagerPlus.ROADS, Map.copyOf(roadState));
			a.dirty();

			dummy = EntityTypes.WANDERING_TRADER.create(level, EntitySpawnReason.COMMAND);
			if (dummy == null) throw new IllegalStateException("Could not create caravan navigation fixture trader");
			Object state = caravanState(dummy, level, a, source);
			int eastLength = RoadRoute.length(roadState, eastKey, source, east);
			int inboundIndex = Math.max(8, eastLength / 2);
			BlockPos inboundCurrent = surfaceFeet(level, RoadRoute.point(roadState, eastKey, source, east, inboundIndex));
			BlockPos inbound = invokeWaypoint(level, a, inboundCurrent, source, state);
			int inboundCursor = indexOf(roadState, eastKey, source, east, inbound);
			check(inboundCursor >= 0 && inboundCursor < inboundIndex
				&& inbound.distSqr(source) < inboundCurrent.distSqr(source), "incoming caravan advances along the canonical road toward its village"); checks++;

			Object returningState = caravanState(dummy, level, a, source);
			int returningIndex = Math.min(6, eastLength - 8);
			BlockPos returningCurrent = surfaceFeet(level, RoadRoute.point(roadState, eastKey, source, east, returningIndex));
			BlockPos returning = invokeWaypoint(level, a, returningCurrent, east, returningState);
			int returningCursor = indexOf(roadState, eastKey, source, east, returning);
			check(returningCursor > returningIndex && returningCursor <= eastLength
				&& returning.distSqr(east) < returningCurrent.distSqr(east), "departing caravan advances along the same road in the reverse direction"); checks++;

			// A route flag and geometry alone are insufficient: without any paved surface the navigator falls back.
			unpave(level, roadState, source, east, eastKey);
			BlockPos missingFallback = invokeWaypoint(level, a, inboundCurrent, east, caravanState(dummy, level, a, source));
			check(missingFallback == null, "unpaved route falls back to the vanilla destination"); checks++;
			pave(level, roadState, source, east, eastKey);

			// A stored route may cross unknown chunks. The test destination stays remote and must never be loaded.
			TradeNetwork.Link remoteLink = new TradeNetwork.Link(dimension, remote, "Fern", 90, true, 0, 0);
			Network.save(a.bellEntity(), TradeNetwork.create(source).withLinks(List.of(remoteLink, ac)));
			String remoteKey = dimension + remote.asLong();
			Map<String, Long> remoteRoadState = new HashMap<>(roadState);
			List<BlockPos> remotePoints = new java.util.ArrayList<>();
			for (int x = source.getX(); x <= remote.getX(); x++) remotePoints.add(new BlockPos(x, 0, source.getZ()));
			storeCanonical(remoteRoadState, remoteKey, source, remote, remotePoints);
			check(RoadRoute.ensure(level, source, remote, remoteRoadState, remoteKey, null, null)
				&& !level.hasChunkAt(remote), "remote canonical route geometry is accepted without loading its endpoint chunk"); checks++;
			a.bellEntity().setAttached(VillagerPlus.ROADS, Map.copyOf(remoteRoadState)); a.dirty();
			BlockPos unloadedFallback = invokeWaypoint(level, a, inboundCurrent, remote, caravanState(dummy, level, a, source));
			check(unloadedFallback == null && !level.hasChunkAt(remote), "unloaded route section uses vanilla fallback without forced chunk loading"); checks++;
		} finally {
			if (dummy != null) dummy.discard();
			restore(level, before);
			BlockEntity restored = level.getBlockEntity(source);
			if (restored != null) {
				restoreAttachment(restored, VillagerPlus.NETWORK, oldSourceNetwork);
				restoreAttachment(restored, VillagerPlus.ROADS, oldSourceRoads);
				restoreAttachment(restored, VillagerPlus.VILLAGE, oldSourceVillage);
				restored.setChanged();
			}
		}
		return checks;
	}

	private static Set<BlockPos> fixturePositions(BlockPos a, BlockPos b, BlockPos c) {
		Set<BlockPos> positions = new HashSet<>();
		corridor(positions, a, b);
		corridor(positions, a, c);
		return positions;
	}

	private static void corridor(Set<BlockPos> positions, BlockPos from, BlockPos to) {
		int dx = Integer.compare(to.getX(), from.getX()), dz = Integer.compare(to.getZ(), from.getZ());
		for (int i = 0; i <= Math.abs(to.getX() - from.getX()) + Math.abs(to.getZ() - from.getZ()); i++) {
			int x = from.getX() + dx * i, z = from.getZ() + dz * i;
			for (int side = -1; side <= 1; side++) {
				int px = x + (dx != 0 ? 0 : side), pz = z + (dz != 0 ? 0 : side);
				for (int y = from.getY() - 2; y <= from.getY() + 2; y++) positions.add(new BlockPos(px, y, pz));
			}
		}
	}

	private static Map<BlockPos, Cell> snapshot(ServerLevel level, Set<BlockPos> positions) {
		Map<BlockPos, Cell> snapshot = new HashMap<>();
		for (BlockPos pos : positions) {
			BlockEntity entity = level.getBlockEntity(pos);
			snapshot.put(pos.immutable(), new Cell(level.getBlockState(pos), entity == null ? null : entity.saveWithFullMetadata(level.registryAccess())));
		}
		return snapshot;
	}

	private static void prepareGround(ServerLevel level, BlockPos a, BlockPos b, BlockPos c) {
		Set<BlockPos> positions = fixturePositions(a, b, c);
		for (BlockPos pos : positions) {
			if (pos.getY() == a.getY() - 1) level.setBlock(pos, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			else if (pos.getY() == a.getY() - 2) level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
			else level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		}
	}

	private static VillageRef placeVillage(ServerLevel level, BlockPos pos) {
		level.setBlock(pos, Blocks.BELL.defaultBlockState(), 3);
		BlockEntity entity = level.getBlockEntity(pos);
		if (!(entity instanceof BellBlockEntity bell)) throw new IllegalStateException("Fixture bell was not created at " + pos);
		VillageData data = new VillageData();
		bell.setAttached(VillagerPlus.VILLAGE, data);
		return new VillageRef(pos, data, bell);
	}

	private static void pave(ServerLevel level, Map<String, Long> roads, BlockPos a, BlockPos b, String key) {
		int length = RoadRoute.length(roads, key, a, b);
		for (int i = 0; i <= length; i++) {
			BlockPos point = RoadRoute.point(roads, key, a, b, i);
			boolean alongX = RoadRoute.alongX(roads, key, a, b, i);
			for (int side = -1; side <= 1; side++) {
				BlockPos paving = point.offset(alongX ? 0 : side, -1, alongX ? side : 0);
				level.setBlock(paving, Blocks.GRAVEL.defaultBlockState(), 3);
			}
		}
	}

	private static void storeCanonical(Map<String, Long> roads, String key, BlockPos a, BlockPos b, List<BlockPos> points) throws Exception {
		Method store = RoadRoute.class.getDeclaredMethod("store", Map.class, String.class, BlockPos.class, BlockPos.class, List.class, long.class);
		store.setAccessible(true);
		store.invoke(null, roads, key, a, b, points, 3L);
	}

	private static void unpave(ServerLevel level, Map<String, Long> roads, BlockPos a, BlockPos b, String key) {
		int length = RoadRoute.length(roads, key, a, b);
		for (int i = 0; i <= length; i++) {
			BlockPos point = RoadRoute.point(roads, key, a, b, i);
			boolean alongX = RoadRoute.alongX(roads, key, a, b, i);
			for (int side = -1; side <= 1; side++) {
				BlockPos paving = point.offset(alongX ? 0 : side, -1, alongX ? side : 0);
				level.setBlock(paving, Blocks.STONE.defaultBlockState(), 3);
			}
		}
	}

	private static BlockPos surfaceFeet(ServerLevel level, BlockPos point) {
		return new BlockPos(point.getX(), point.getY(), point.getZ());
	}

	private static Object caravanState(WanderingTrader trader, ServerLevel level, VillageRef ref, BlockPos spawn) throws Exception {
		Class<?> stateType = Class.forName("de.villagerplus.village.Caravan$State");
		Constructor<?> constructor = stateType.getDeclaredConstructor(WanderingTrader.class, ServerLevel.class, VillageRef.class, BlockPos.class, int.class);
		constructor.setAccessible(true);
		return constructor.newInstance(trader, level, ref, spawn, 0);
	}

	private static BlockPos invokeWaypoint(ServerLevel level, VillageRef ref, BlockPos current, BlockPos destination, Object state) throws Exception {
		Method method = Caravan.class.getDeclaredMethod("roadWaypoint", ServerLevel.class, VillageRef.class, BlockPos.class, BlockPos.class, state.getClass());
		method.setAccessible(true);
		return (BlockPos) method.invoke(null, level, ref, current, destination, state);
	}

	private static int indexOf(Map<String, Long> state, String key, BlockPos a, BlockPos b, BlockPos waypoint) {
		if (waypoint == null) return -1;
		int length = RoadRoute.length(state, key, a, b);
		for (int i = 0; i <= length; i++) {
			BlockPos point = RoadRoute.point(state, key, a, b, i);
			if (point.getX() == waypoint.getX() && point.getZ() == waypoint.getZ()) return i;
		}
		return -1;
	}

	private static void restore(ServerLevel level, Map<BlockPos, Cell> before) {
		for (BlockPos pos : before.keySet()) level.removeBlockEntity(pos);
		before.forEach((pos, cell) -> level.setBlock(pos, cell.state(), 3));
		before.forEach((pos, cell) -> {
			if (cell.data() == null) return;
			BlockEntity entity = BlockEntity.loadStatic(pos, cell.state(), cell.data().copy(), level.registryAccess());
			if (entity != null) level.setBlockEntity(entity);
		});
	}

	private static <T> T attached(BlockEntity entity, AttachmentType<T> type) {
		return entity == null ? null : entity.getAttached(type);
	}

	private static <T> void restoreAttachment(BlockEntity entity, AttachmentType<T> type, Object value) {
		if (value == null) entity.removeAttached(type);
		else entity.setAttached(type, (T) value);
	}

	private static void check(boolean value, String message) {
		if (!value) throw new IllegalStateException("Caravan road: " + message);
	}

	private record Cell(BlockState state, CompoundTag data) { }
}
