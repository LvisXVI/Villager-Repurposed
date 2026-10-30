package de.villagerplus.dev;

import com.mojang.serialization.JsonOps;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Built;
import de.villagerplus.village.BuildingRelocation;
import de.villagerplus.village.VillageRef;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.core.GlobalPos;

/** Focused rejection and registration-persistence checks for map building moves. */
public final class BuildingRelocationChecks {
	private BuildingRelocationChecks() { }
	public static int run(ServerLevel level, VillageRef ref) {
		int passed = 0;
		List<Built> old = ref.bellEntity().getAttachedOrElse(VillagerPlus.BUILDINGS, List.of());
		Villager witness = EntityTypes.VILLAGER.spawn(level, ref.bell().offset(8, 0, 8), EntitySpawnReason.COMMAND);
		if (witness == null) throw new IllegalStateException("BUILDING RELOCATION CHECK FAILED: unable to spawn plan witness");
		witness.addTag("vp090_fixture");
		witness.setNoAi(true);
		witness.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), ref.bell()));
		try {
			var fake = new Built("house", ref.bell().offset(12, 0, 0), 0, 0, List.of());
			ref.bellEntity().setAttached(VillagerPlus.BUILDINGS, List.of(fake));
			String reject = BuildingRelocation.move(level, ref, fake.origin(), ref.bell().offset(140, 0, 0));
			check(reject.contains("außerhalb"), "rejects a move outside the village bounds before block changes"); passed++;
			check(ref.bellEntity().getAttached(VillagerPlus.BUILDINGS).equals(List.of(fake)), "rejected move preserves the building registration"); passed++;
			var codec = Built.CODEC.listOf();
			var encoded = codec.encodeStart(JsonOps.INSTANCE, List.of(fake)).getOrThrow();
			check(codec.parse(JsonOps.INSTANCE, encoded).getOrThrow().equals(List.of(fake)), "building origin and palette persist through codec round-trip"); passed++;
		} finally {
			witness.discard();
			de.villagerplus.village.Villages.forget(witness.getUUID());
			ref.bellEntity().setAttached(VillagerPlus.BUILDINGS, old);
			ref.dirty();
		}
		return passed;
	}
	private static void check(boolean ok, String label) {
		if (!ok) throw new IllegalStateException("BUILDING RELOCATION CHECK FAILED: " + label);
		System.out.println("BUILDING RELOCATION PASS " + label);
	}
}
