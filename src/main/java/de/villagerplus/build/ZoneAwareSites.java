package de.villagerplus.build;

import de.villagerplus.VillagerPlus;
import de.villagerplus.village.Sites;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.ZonePolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;

/** Zone-aware construction siting. The two-block parcel border reserves room for scaffold and the workbench. */
public final class ZoneAwareSites {
	public static final int CLEARANCE = 2;
	private ZoneAwareSites() { }

	/** Outdoor communal infrastructure and agriculture retain their own placement policies. */
	public static String kindFor(String type) {
		if (Blueprints.HOUSE.equals(type)) return "housing";
		if (Blueprints.PEN.equals(type)) return "pasture";
		return Blueprints.STORAGE.equals(type) || Blueprints.SCHOOL.equals(type) || Blueprints.special(type)
			|| Blueprints.TOWNHALL.equals(type) ? "work" : null;
	}

	private static List<ZonePolicy.Zone> zones(VillageRef ref, String type) {
		String kind = kindFor(type);
		if (ref == null || kind == null) return List.of();
		return ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES, List.of()).stream()
			.filter(z -> kind.equals(z.kind()) && ZonePolicy.validBounds(ref, z.kind(), z.minX(), z.minZ(), z.maxX(), z.maxZ()))
			.limit(ZonePolicy.MAX_ZONES).toList();
	}

	public static boolean allowsRect(VillageRef ref, String type, BlockPos anchor, int minX, int maxX, int minZ, int maxZ) {
		List<ZonePolicy.Zone> zones = zones(ref, type);
		return zones.isEmpty() || zones.stream().anyMatch(z -> (long)anchor.getX() + minX - CLEARANCE >= z.minX()
			&& (long)anchor.getX() + maxX + CLEARANCE <= z.maxX()
			&& (long)anchor.getZ() + minZ - CLEARANCE >= z.minZ()
			&& (long)anchor.getZ() + maxZ + CLEARANCE <= z.maxZ());
	}

	/** Includes every planned decoration/roof/porch block, not only the Architect's main floor. */
	public static boolean allowsPlan(VillageRef ref, String type, List<Step> steps) {
		if (zones(ref, type).isEmpty()) return true;
		if (steps == null || steps.isEmpty()) return false;
		int minX = steps.stream().mapToInt(s -> s.pos().getX()).min().orElseThrow();
		int maxX = steps.stream().mapToInt(s -> s.pos().getX()).max().orElseThrow();
		int minZ = steps.stream().mapToInt(s -> s.pos().getZ()).min().orElseThrow();
		int maxZ = steps.stream().mapToInt(s -> s.pos().getZ()).max().orElseThrow();
		return allowsRect(ref, type, BlockPos.ZERO, minX, maxX, minZ, maxZ);
	}

	public static BlockPos candidate(ServerLevel level, VillageRef ref, String type, RandomSource random, int min, int max) {
		if (zones(ref, type).isEmpty()) return Sites.candidate(level, ref.bell(), random, min, max);
		List<BlockPos> choices = candidates(ref, type, random, 0, 0, 0, 0);
		for (BlockPos p : choices) if (level.hasChunkAt(p)) return surface(level, p);
		return null;
	}

	public static BlockPos tolerantAnchor(ServerLevel level, VillageRef ref, String type, BlockPos candidate,
		int minX, int maxX, int minZ, int maxZ, int headroom) {
		return allowsRect(ref, type, candidate, minX, maxX, minZ, maxZ)
			? Sites.tolerantAnchor(level, candidate, minX, maxX, minZ, maxZ, headroom) : null;
	}

	public static BlockPos find(ServerLevel level, VillageRef ref, String type, RandomSource random, int min, int max,
		int half, int headroom, int attempts, Predicate<BlockPos> planFits) {
		if (zones(ref, type).isEmpty()) return Sites.find(level, ref.bell(), random, min, max, half, headroom, attempts);
		return findZoned(level, ref, type, random, -half, half, -half, half, headroom, attempts, false, planFits);
	}

	public static BlockPos findTolerant(ServerLevel level, VillageRef ref, String type, RandomSource random, int min, int max,
		int minX, int maxX, int minZ, int maxZ, int headroom, int attempts, Predicate<BlockPos> planFits) {
		if (zones(ref, type).isEmpty()) return Sites.findTolerant(level, ref.bell(), random, min, max, minX, maxX, minZ, maxZ, headroom, attempts);
		return findZoned(level, ref, type, random, minX, maxX, minZ, maxZ, headroom, attempts, true, planFits);
	}

	public static BlockPos findSystematic(ServerLevel level, VillageRef ref, String type, RandomSource random, int min, int max,
		int minX, int maxX, int minZ, int maxZ, int headroom, Predicate<BlockPos> planFits) {
		if (zones(ref, type).isEmpty()) return Sites.findSystematic(level, ref.bell(), random, min, max, minX, maxX, minZ, maxZ, headroom, planFits);
		return findZoned(level, ref, type, random, minX, maxX, minZ, maxZ, headroom, 4096, false, planFits);
	}

	public static BlockPos findTolerantSystematic(ServerLevel level, VillageRef ref, String type, RandomSource random, int min, int max,
		int minX, int maxX, int minZ, int maxZ, int headroom, Predicate<BlockPos> planFits) {
		if (zones(ref, type).isEmpty()) return Sites.findTolerantSystematic(level, ref.bell(), random, min, max, minX, maxX, minZ, maxZ, headroom, planFits);
		return findZoned(level, ref, type, random, minX, maxX, minZ, maxZ, headroom, 4096, true, planFits);
	}

	/** Prefer civic plots closest to the measured town centre, while still applying the normal zone and full-plan guards. */
	public static BlockPos findCentral(ServerLevel level, VillageRef ref, String type, BlockPos center, RandomSource random, int radius,
		int minX, int maxX, int minZ, int maxZ, int headroom, Predicate<BlockPos> planFits) {
		if (zones(ref, type).isEmpty()) return Sites.findSystematic(level, center, random, 0, radius, minX, maxX, minZ, maxZ, headroom, planFits);
		return findZonedCentral(level, ref, type, center, random, minX, maxX, minZ, maxZ, headroom, false, planFits);
	}

	public static BlockPos findTolerantCentral(ServerLevel level, VillageRef ref, String type, BlockPos center, RandomSource random, int radius,
		int minX, int maxX, int minZ, int maxZ, int headroom, Predicate<BlockPos> planFits) {
		if (zones(ref, type).isEmpty()) return Sites.findTolerantSystematic(level, center, random, 0, radius, minX, maxX, minZ, maxZ, headroom, planFits);
		return findZonedCentral(level, ref, type, center, random, minX, maxX, minZ, maxZ, headroom, true, planFits);
	}

	private static BlockPos findZonedCentral(ServerLevel level, VillageRef ref, String type, BlockPos center, RandomSource random,
		int minX, int maxX, int minZ, int maxZ, int headroom, boolean tolerant, Predicate<BlockPos> planFits) {
		List<BlockPos> choices = candidates(ref, type, random, minX, maxX, minZ, maxZ);
		choices.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(center)));
		int checked = 0;
		for (BlockPos choice : choices) {
			if (!level.hasChunkAt(choice)) continue;
			if (checked++ >= 4096) break;
			BlockPos anchor = surface(level, choice);
			if (tolerant) anchor = Sites.tolerantAnchor(level, anchor, minX, maxX, minZ, maxZ, headroom);
			else if (!Sites.okRect(level, anchor, minX, maxX, minZ, maxZ, headroom)) anchor = null;
			if (anchor != null && planFits.test(anchor)) return anchor;
		}
		return null;
	}

	private static BlockPos findZoned(ServerLevel level, VillageRef ref, String type, RandomSource random,
		int minX, int maxX, int minZ, int maxZ, int headroom, int attempts, boolean tolerant, Predicate<BlockPos> planFits) {
		int checked = 0;
		for (BlockPos choice : candidates(ref, type, random, minX, maxX, minZ, maxZ)) {
			if (!level.hasChunkAt(choice)) continue;
			if (checked++ >= attempts) break;
			BlockPos anchor = surface(level, choice);
			if (tolerant) anchor = Sites.tolerantAnchor(level, anchor, minX, maxX, minZ, maxZ, headroom);
			else if (!Sites.okRect(level, anchor, minX, maxX, minZ, maxZ, headroom)) anchor = null;
			if (anchor != null && planFits.test(anchor)) return anchor;
		}
		return null;
	}

	private static BlockPos surface(ServerLevel level, BlockPos p) {
		return new BlockPos(p.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()), p.getZ());
	}

	private static List<BlockPos> candidates(VillageRef ref, String type, RandomSource random, int minX, int maxX, int minZ, int maxZ) {
		List<BlockPos> choices = new ArrayList<>();
		HashSet<Long> seen = new HashSet<>();
		for (ZonePolicy.Zone zone : zones(ref, type)) {
			for (int x = zone.minX() - minX + CLEARANCE; x <= zone.maxX() - maxX - CLEARANCE; x++) {
				for (int z = zone.minZ() - minZ + CLEARANCE; z <= zone.maxZ() - maxZ - CLEARANCE; z++) {
					if (seen.add(BlockPos.asLong(x, 0, z))) choices.add(new BlockPos(x, ref.bell().getY(), z));
				}
			}
		}
		Collections.shuffle(choices, new java.util.Random(random.nextLong()));
		return choices;
	}
}
