package de.villagerplus.dev;

import com.mojang.serialization.JsonOps;
import de.villagerplus.VillagerPlus;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.ZonePolicy;
import java.util.List;

/** Focused persistence and server-boundary checks for map zones. */
public final class ZonePolicyChecks {
	private ZonePolicyChecks() { }
	public static int run(VillageRef ref) {
		int passed = 0;
		check(ref != null && ZonePolicy.validBounds(ref, "farm", ref.bell().getX() - 2, ref.bell().getZ() - 3, ref.bell().getX() + 2, ref.bell().getZ() + 3), "accepts a bounded zone inside the village radius"); passed++;
		check(ref != null && ZonePolicy.validBounds(ref, "pasture", ref.bell().getX() - 4, ref.bell().getZ() - 4, ref.bell().getX() + 4, ref.bell().getZ() + 4), "accepts a bounded pasture zone"); passed++;
		check(!ZonePolicy.validBounds(ref, "farm", ref.bell().getX() - 65, ref.bell().getZ(), ref.bell().getX(), ref.bell().getZ()), "rejects zones extending beyond the server radius"); passed++;
		check(!ZonePolicy.validBounds(ref, "unknown", ref.bell().getX(), ref.bell().getZ(), ref.bell().getX(), ref.bell().getZ()), "rejects unknown zone types"); passed++;
		var zone = new ZonePolicy.Zone("test-zone", "farm", ref.bell().getX() - 2, ref.bell().getZ() - 3, ref.bell().getX() + 2, ref.bell().getZ() + 3);
		var encoded = ZonePolicy.Zone.CODEC.encodeStart(JsonOps.INSTANCE, zone).getOrThrow();
		check(ZonePolicy.Zone.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().equals(zone), "zone bounds and category survive persistent codec round-trip"); passed++;
		List<ZonePolicy.Zone> old = ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES, List.of());
		try {
			ref.bellEntity().setAttached(VillagerPlus.ZONES, List.of(zone));
			var codec = ZonePolicy.Zone.CODEC.listOf();
			var saved = codec.encodeStart(JsonOps.INSTANCE, ref.bellEntity().getAttached(VillagerPlus.ZONES)).getOrThrow();
			var loaded = codec.parse(JsonOps.INSTANCE, saved).getOrThrow();
			check(ref.bellEntity().getAttached(VillagerPlus.ZONES).equals(loaded), "village zone attachment persists across serialization"); passed++;
		} finally { ref.bellEntity().setAttached(VillagerPlus.ZONES, old); ref.dirty(); }
		return passed;
	}
	private static void check(boolean ok, String label) {
		if (!ok) throw new IllegalStateException("ZONE POLICY CHECK FAILED: " + label);
		System.out.println("ZONE POLICY PASS " + label);
	}
}
