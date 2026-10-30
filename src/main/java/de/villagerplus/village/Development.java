package de.villagerplus.village;

import de.villagerplus.Config;
import de.villagerplus.build.Builder;
import de.villagerplus.build.Built;
import de.villagerplus.build.Blueprints;
import de.villagerplus.build.Architect;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.core.registries.Registries;

/** Shared needs and extent calculations for the village development planner. */
public final class Development {
	/** Large enough for spread-out generated villages while keeping wall work bounded. */
	public static final int MAX_WALL_RADIUS = 192;
	private static final int WALL_CLEARANCE = 14;
	private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
	private static final java.util.Map<Long, Long> WALL_LIMIT_WARNED = new HashMap<>();

	private Development() {}

	/** Maintain a small housing reserve even when the population has been stable. */
	public static boolean needsHousing(long beds, int population) {
		return population > 0 && population < Config.MAX_VILLAGERS && beds <= population;
	}

	/** Start storage before the village chest becomes completely full; never evict an active plan. */
	public static boolean needsStorage(Container combined, VillageData data, ServerLevel level) {
		if (combined == null || !storageSlotNeeded(data, level)) return false;
		int occupied = 0;
		for (int i = 0; i < combined.getContainerSize(); i++) {
			if (!combined.getItem(i).isEmpty()) occupied++;
		}
		return occupied * 4 >= combined.getContainerSize() * 3;
	}

	private static boolean storageSlotNeeded(VillageData data, ServerLevel level) {
		if (data.extra.lager.isEmpty()) return true;
		return Lager.chestWithRoom(level, data) == null && data.extra.lagerMore.size() < Lager.MAX_EXTRA_SHEDS;
	}

	/** Radius enclosing all known original structure pieces, homes, and mod-built structures. */
	public static int neededWallRadius(ServerLevel level, VillageRef ref) {
		BlockPos bell = ref.bell();
		BoundingBox footprint = bounds(level, ref);
		int extent = edgeDistance(footprint, bell);
		if (extent < Config.WALL_MIN_VILLAGE_RADIUS) return 0;
		int radius = extent + WALL_CLEARANCE;
		// 8 * radius is the persisted column cursor; radius stays in its low byte beside the prior radius.
		if (radius > MAX_WALL_RADIUS) {
			long now = level.getGameTime();
			if (now >= WALL_LIMIT_WARNED.getOrDefault(bell.asLong(), 0L)) {
				WALL_LIMIT_WARNED.put(bell.asLong(), now + 12000L);
				LOGGER.warn("Village at {} spans {} blocks; refusing to start a wall with the {}-block safety limit. No wall radius was clipped.",
					bell.toShortString(), extent, MAX_WALL_RADIUS);
			}
			return 0;
		}
		return radius;
	}

	/** Combined horizontal footprint of the bell, original generated village, and tracked mod buildings. */
	public static BoundingBox bounds(ServerLevel level, VillageRef ref) {
		BlockPos bell = ref.bell();
		BoundingBox observed = new BoundingBox(bell.getX(), bell.getY(), bell.getZ(), bell.getX(), bell.getY(), bell.getZ());
		Set<String> visited = new HashSet<>();
		var structureRegistry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		int minChunkX = (bell.getX() - Config.VILLAGE_RADIUS - 16) >> 4;
		int maxChunkX = (bell.getX() + Config.VILLAGE_RADIUS + 16) >> 4;
		int minChunkZ = (bell.getZ() - Config.VILLAGE_RADIUS - 16) >> 4;
		int maxChunkZ = (bell.getZ() + Config.VILLAGE_RADIUS + 16) >> 4;
		for (int cx = minChunkX; cx <= maxChunkX; cx++) {
			for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
				var chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) continue;
				for (var entry : chunk.getAllStarts().entrySet()) {
					var id = structureRegistry.getKey(entry.getKey());
					if (id == null || !id.getPath().toLowerCase(java.util.Locale.ROOT).contains("village")) continue;
					StructureStart start = entry.getValue();
					if (!start.isValid() || !ownsVillageStart(start.getBoundingBox(), bell)) continue;
					for (StructurePiece piece : start.getPieces()) {
						BoundingBox box = piece.getBoundingBox();
						String key = box.minX() + ":" + box.minZ() + ":" + box.maxX() + ":" + box.maxZ();
						if (visited.add(key)) observed = union(observed, box);
					}
				}
			}
		}
		var meetings = level.getPoiManager().getInRange(h -> h.is(PoiTypes.MEETING), bell,
			Config.VILLAGE_RADIUS * 3, PoiManager.Occupancy.ANY).map(p -> p.getPos().immutable()).toList();
		var homes = level.getPoiManager().getInRange(h -> h.is(PoiTypes.HOME), bell,
			Config.VILLAGE_RADIUS + 16, PoiManager.Occupancy.ANY).map(p -> p.getPos().immutable()).toList();
		for (BlockPos pos : homes) {
			if (!belongsToBell(pos, bell, meetings)) continue;
			observed = union(observed, new BoundingBox(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ()));
		}
		for (Built built : Builder.builtOf(ref)) {
			int half = builtHalf(built);
			observed = union(observed, new BoundingBox(built.origin().getX() - half, built.origin().getY(), built.origin().getZ() - half,
				built.origin().getX() + half, built.origin().getY() + 16, built.origin().getZ() + half));
		}
		// Include the current civic center and managed additions even when their chunks/POIs are not loaded.
		BlockPos plaza = Market.of(ref).plaza().orElse(null);
		if (plaza != null) observed = union(observed, footprint(plaza, 12, 8));
		for (MarketData.Shop shop : Market.of(ref).shops()) observed = union(observed, footprint(shop.origin(), 10, 8));
		for (BlockPos origin : Specials.all(ref).values()) observed = union(observed, footprint(origin, 12, 12));
		for (BlockPos center : Lager.centers(ref.data())) observed = union(observed, footprint(center, 8, 8));
		for (BlockPos origin : new BlockPos[] {
			ref.data().chest.orElse(null), ref.data().planOrigin.orElse(null), ref.data().extra.school.orElse(null),
			ref.data().extra.board.orElse(null), ref.data().extra.pen.orElse(null)
		}) if (origin != null) observed = union(observed, footprint(origin, 4, 4));
		VillageExtent previous = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.VILLAGE_EXTENT, null);
		VillageExtent remembered = previous == null ? VillageExtent.of(observed) : previous.union(observed);
		if (!remembered.equals(previous)) {
			ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.VILLAGE_EXTENT, remembered);
			ref.dirty();
		}
		return remembered.box();
	}

	private static BoundingBox union(BoundingBox a, BoundingBox b) {
		return new BoundingBox(Math.min(a.minX(), b.minX()), Math.min(a.minY(), b.minY()), Math.min(a.minZ(), b.minZ()),
			Math.max(a.maxX(), b.maxX()), Math.max(a.maxY(), b.maxY()), Math.max(a.maxZ(), b.maxZ()));
	}

	private static BoundingBox footprint(BlockPos center, int radius, int height) {
		return new BoundingBox(center.getX() - radius, center.getY() - 2, center.getZ() - radius,
			center.getX() + radius, center.getY() + height, center.getZ() + radius);
	}

	/** A generated village start belongs to this bell only when its complete start footprint contains the bell in X/Z. */
	public static boolean ownsVillageStart(BoundingBox box, BlockPos pos) {
		return pos.getX() >= box.minX() && pos.getX() <= box.maxX() && pos.getZ() >= box.minZ() && pos.getZ() <= box.maxZ();
	}

	private static boolean belongsToBell(BlockPos point, BlockPos bell, java.util.List<BlockPos> meetings) {
		if (meetings.isEmpty()) return false;
		BlockPos nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (BlockPos meeting : meetings) {
			double distance = meeting.distSqr(point);
			if (distance < nearestDistance) {
				nearest = meeting;
				nearestDistance = distance;
			}
		}
		return bell.equals(nearest);
	}

	/** Horizontal midpoint of {@link #bounds}; keeps the village bell's Y for map/plaza targeting. */
	public static BlockPos center(ServerLevel level, VillageRef ref) {
		BoundingBox box = bounds(level, ref);
		return new BlockPos(box.minX() + (box.maxX() - box.minX()) / 2, ref.bell().getY(),
			box.minZ() + (box.maxZ() - box.minZ()) / 2);
	}

	private static int edgeDistance(BoundingBox box, BlockPos bell) {
		return Math.max(Math.max(Math.abs(box.minX() - bell.getX()), Math.abs(box.maxX() - bell.getX())),
			Math.max(Math.abs(box.minZ() - bell.getZ()), Math.abs(box.maxZ() - bell.getZ())));
	}

	private static int builtHalf(Built built) {
		if (!built.type().equals(Blueprints.HOUSE) && !built.type().equals(Blueprints.STORAGE)
			&& !built.type().equals(Blueprints.SCHOOL) && !Blueprints.special(built.type())) return 4;
		Architect.Kind kind = switch (built.type()) {
			case Blueprints.SCHOOL -> Architect.Kind.SCHOOL;
			case Blueprints.STORAGE -> Architect.Kind.STORAGE;
			case Blueprints.GRANARY -> Architect.Kind.GRANARY;
			case Blueprints.TOOLSHED -> Architect.Kind.TOOLSHED;
			case Blueprints.KITCHEN -> Architect.Kind.KITCHEN;
			case Blueprints.STATION -> Architect.Kind.STATION;
			default -> Architect.Kind.HOUSE;
		};
		return Architect.design(kind, built.variant() >>> 2).half() + 3;
	}
}
