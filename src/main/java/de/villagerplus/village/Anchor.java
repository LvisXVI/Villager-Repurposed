package de.villagerplus.village;

import de.villagerplus.Config;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Haelt Doerfer am Leben: Die Chunks rund um die Dorfglocke und entlang der aktiven Mine werden dauerhaft geladen (Force-Load,
 * bleibt ueber Neustarts erhalten). Damit arbeiten, bauen, minen und handeln die Villager auch dann weiter, wenn kein Spieler in
 * der Naehe ist. Abschaltbar ueber {@code Config.KEEP_VILLAGES_LOADED}.
 */
public final class Anchor {
	/** Short-lived work tickets load and simulate the active wall corridor without changing persistent /forceload state. */
	public static final TicketType WALL_WORK = new TicketType(240L,
		TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
	private static boolean registered;
	public static void register() {
		if (!registered) {
			Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.fromNamespaceAndPath("villagerplus", "wall_work"), WALL_WORK);
			registered = true;
		}
	}
	private static final Map<Long, Long> LAST = new HashMap<>();
	private static final Map<AnchorKey, Long> LAST_BY_VILLAGE = new HashMap<>();
	private static final Map<AnchorKey, Long> RING = new HashMap<>();
	private static final Map<AnchorKey, Long> WARNED = new HashMap<>();
	private static final int MAX_FORCED_CHUNKS = 256;
	private static final int FOOTPRINT_MARGIN_CHUNKS = 1;
	private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
	private record AnchorKey(ResourceKey<Level> dimension, long pos) { }

	private Anchor() {
	}

	public static void keepLoaded(ServerLevel level, VillageRef ref, long now) {
		if (!Config.KEEP_VILLAGES_LOADED) {
			return;
		}
		AnchorKey key = new AnchorKey(level.dimension(), ref.bell().asLong());
		Long last = LAST_BY_VILLAGE.get(key);
		if (last != null && now - last < 100L) {
			return;
		}
		LAST_BY_VILLAGE.put(key, now);
		keepWallWorkLoaded(level, ref, ref.bell());
		// Generated CTOV villages can extend far beyond a bell-centered fixed radius.
		// Load the known structure/POI/built footprint plus a one-chunk pathing margin,
		// while keeping a deterministic hard cap on forced chunks per village.
		Long ringDone = RING.get(key);
		if (ringDone == null || now - ringDone > 12000L) {
			RING.put(key, now);
			forceFootprint(level, ref, key, now);
		}
		// Aktive Mine: die Chunks entlang des Stollens (bis ein Stueck voraus).
		ref.data().mine.filter(m -> !m.done).ifPresent(m -> {
			Direction dir = Direction.from2DDataValue(m.dir);
			for (int i = 0; i <= m.index + 48; i += 8) {
				BlockPos p = m.entrance.relative(dir, i);
				level.setChunkForced(p.getX() >> 4, p.getZ() >> 4, true);
			}
		});
	}

	/** Refresh the current wall segment during work and routine phases, including old-wall removal after expansion. */
	public static void keepWallWorkLoaded(ServerLevel level, VillageRef ref, BlockPos worker) {
		if (!Config.KEEP_VILLAGES_LOADED || !de.villagerplus.build.Blueprints.WALL.equals(ref.data().planType)) return;
		int radius = ref.data().planVariant & 0xFF;
		int cursor = ref.data().planStep;
		if (radius <= 0 || radius > Development.MAX_WALL_RADIUS) return;
		if (cursor >= 8 * radius) {
			int old = (ref.data().planVariant >>> 8) & 0xFF;
			cursor -= 8 * radius;
			if (old <= 0 || old > Development.MAX_WALL_RADIUS || cursor >= 8 * old) return;
			radius = old;
		}
		for (ChunkPos chunk : wallWorkChunks(ref.bell(), worker, radius, cursor)) {
			level.getChunkSource().addTicketWithRadius(WALL_WORK, chunk, 2);
		}
	}

	/** Bounded path from the worker to the next segment; the expiring ticket radius supplies a two-chunk margin. */
	public static java.util.Set<ChunkPos> wallWorkChunks(BlockPos bell, BlockPos worker, int radius, int cursor) {
		if (radius <= 0 || radius > Development.MAX_WALL_RADIUS || cursor < 0 || cursor >= 8 * radius) return java.util.Set.of();
		java.util.Set<ChunkPos> chunks = new java.util.LinkedHashSet<>();
		int[] offset = de.villagerplus.build.Wall.ringOffset(radius, cursor);
		Direction inward = de.villagerplus.build.Wall.inward(radius, cursor);
		int targetX = (bell.getX() + offset[0] + inward.getStepX() * 11) >> 4;
		int targetZ = (bell.getZ() + offset[1] + inward.getStepZ() * 11) >> 4;
		int fromX = worker.getX() >> 4, fromZ = worker.getZ() >> 4;
		int steps = Math.max(Math.abs(targetX - fromX), Math.abs(targetZ - fromZ));
		// A legal village wall is at most 192 blocks from its bell. Do not accept unbounded remote-worker corridors.
		if (steps <= 48) {
			for (int i = 0; i <= steps; i++) {
				int x = steps == 0 ? targetX : fromX + (int)Math.round((double)(targetX - fromX) * i / steps);
				int z = steps == 0 ? targetZ : fromZ + (int)Math.round((double)(targetZ - fromZ) * i / steps);
				chunks.add(new ChunkPos(x, z));
			}
		}
		for (int i = cursor; i < Math.min(cursor + 4, 8 * radius); i++) {
			int[] next = de.villagerplus.build.Wall.ringOffset(radius, i);
			chunks.add(new ChunkPos((bell.getX() + next[0]) >> 4, (bell.getZ() + next[1]) >> 4));
		}
		return java.util.Set.copyOf(chunks);
	}

	private static void forceFootprint(ServerLevel level, VillageRef ref, AnchorKey key, long now) {
		BoundingBox box = Development.bounds(level, ref);
		int bellChunkX = ref.bell().getX() >> 4;
		int bellChunkZ = ref.bell().getZ() >> 4;
		int minX = Math.min(box.minX() >> 4, bellChunkX - Config.ANCHOR_RADIUS_CHUNKS) - FOOTPRINT_MARGIN_CHUNKS;
		int maxX = Math.max(box.maxX() >> 4, bellChunkX + Config.ANCHOR_RADIUS_CHUNKS) + FOOTPRINT_MARGIN_CHUNKS;
		int minZ = Math.min(box.minZ() >> 4, bellChunkZ - Config.ANCHOR_RADIUS_CHUNKS) - FOOTPRINT_MARGIN_CHUNKS;
		int maxZ = Math.max(box.maxZ() >> 4, bellChunkZ + Config.ANCHOR_RADIUS_CHUNKS) + FOOTPRINT_MARGIN_CHUNKS;
		var chunks = new ArrayList<int[]>();
		for (int cx = minX; cx <= maxX; cx++) {
			for (int cz = minZ; cz <= maxZ; cz++) chunks.add(new int[] {cx, cz});
		}
		chunks.sort(Comparator.<int[]>comparingLong(c -> (long)(c[0] - bellChunkX) * (c[0] - bellChunkX)
			+ (long)(c[1] - bellChunkZ) * (c[1] - bellChunkZ)).thenComparingInt(c -> c[0]).thenComparingInt(c -> c[1]));
		int count = Math.min(MAX_FORCED_CHUNKS, chunks.size());
		for (int i = 0; i < count; i++) level.setChunkForced(chunks.get(i)[0], chunks.get(i)[1], true);
		if (chunks.size() > MAX_FORCED_CHUNKS && now >= WARNED.getOrDefault(key, 0L)) {
			WARNED.put(key, now + 12000L);
			LOGGER.warn("Village footprint at {} spans {} chunks; forced-loading the nearest {} only (hard cap).",
				ref.bell().toShortString(), chunks.size(), MAX_FORCED_CHUNKS);
		}
	}
}
