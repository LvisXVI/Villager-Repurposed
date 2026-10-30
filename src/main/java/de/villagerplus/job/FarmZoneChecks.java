package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.ZonePolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Development-world checks that exercise the real cluster search against persisted farm zones. */
public final class FarmZoneChecks {
	private FarmZoneChecks() { }

	public static int run(ServerLevel level, VillageRef ref) {
		int checks = 0;
		List<ZonePolicy.Zone> old = ref.bellEntity().getAttached(VillagerPlus.ZONES);
		Map<BlockPos, BlockState> before = new LinkedHashMap<>();
		BlockPos legacy = ref.bell().offset(-8, 0, -8), zoned = ref.bell().offset(-52, 0, -52);
		prepare(level, legacy, before);
		prepare(level, zoned, before);
		try {
			zones(ref, List.of());
			check(legacy.equals(FarmerJob.findClusterSite(level, ref, ref.bell())),
				"no-zone worlds preserve the established first safe cluster location"); checks++;
			zones(ref, List.of(zone("work", legacy, 2)));
			check(legacy.equals(FarmerJob.findClusterSite(level, ref, ref.bell())),
				"non-farm zones do not redirect field placement"); checks++;

			zones(ref, List.of(zone("farm", zoned, 2)));
			check(zoned.equals(FarmerJob.findClusterSite(level, ref, ref.bell())),
				"a farm zone redirects the real search away from a nearer unzoned plot"); checks++;
			check(FarmerJob.clusterAllowedByZones(ref, zoned) && !FarmerJob.clusterAllowedByZones(ref, zoned.east()),
				"crop, irrigation and fenced perimeter all fit inside one farm zone"); checks++;
			BlockPos obstacle = zoned.east(2);
			level.setBlock(obstacle, Blocks.CHEST.defaultBlockState(), 3);
			check(FarmerJob.findClusterSite(level, ref, ref.bell()) == null,
				"a blocked farm zone pauses expansion instead of falling back outside its border"); checks++;
			level.setBlock(obstacle, Blocks.AIR.defaultBlockState(), 3);

			zones(ref, List.of(new ZonePolicy.Zone("small", "farm", zoned.getX() - 2, zoned.getZ() - 2,
				zoned.getX() + 1, zoned.getZ() + 2)));
			check(FarmerJob.findClusterSite(level, ref, ref.bell()) == null,
				"an undersized farm zone cannot spill a cluster across its edge"); checks++;
			zones(ref, List.of(zone("farm", legacy, 2)));
			check(!FarmerJob.clusterAllowedByZones(ref, zoned)
				&& legacy.equals(FarmerJob.findClusterSite(level, ref, ref.bell())),
				"editing a zone invalidates an old walking target before construction commits"); checks++;
			zones(ref, List.of(new ZonePolicy.Zone("invalid", "farm", ref.bell().getX() + 80, ref.bell().getZ(),
				ref.bell().getX() + 90, ref.bell().getZ() + 10)));
			check(legacy.equals(FarmerJob.findClusterSite(level, ref, ref.bell())),
				"malformed persisted zones are ignored rather than expanding the permitted village area"); checks++;
			return checks;
		} finally {
			before.forEach((pos, state) -> level.setBlock(pos, state, 3));
			if (old == null) ref.bellEntity().removeAttached(VillagerPlus.ZONES);
			else ref.bellEntity().setAttached(VillagerPlus.ZONES, old);
			ref.dirty();
		}
	}

	private static ZonePolicy.Zone zone(String kind, BlockPos center, int radius) {
		return new ZonePolicy.Zone("farm-search-check", kind, center.getX() - radius, center.getZ() - radius,
			center.getX() + radius, center.getZ() + radius);
	}

	private static void zones(VillageRef ref, List<ZonePolicy.Zone> zones) {
		ref.bellEntity().setAttached(VillagerPlus.ZONES, zones);
	}

	private static void prepare(ServerLevel level, BlockPos site, Map<BlockPos, BlockState> before) {
		for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
			BlockPos cell = site.offset(dx, 0, dz);
			level.getChunkAt(cell);
			for (int y = -1; y <= 3; y++) before.putIfAbsent(cell.above(y), level.getBlockState(cell.above(y)));
			level.setBlock(cell.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			for (int y = 0; y <= 3; y++) level.setBlock(cell.above(y), Blocks.AIR.defaultBlockState(), 3);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("FARM ZONE CHECK FAILED: " + message);
	}
}
