package de.villagerplus.dev;

import com.mojang.serialization.JsonOps;
import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.village.Staffing;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Villages;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;

/** Focused runtime assertions for reversible emergency job reassignment. */
public final class StaffingChecks {
	private StaffingChecks() {}

	public static int run(ServerLevel level, VillageRef ref) {
		List<Villager> fixtures = new ArrayList<>();
		try {
			var librarian = ResourceKey.create(Registries.VILLAGER_PROFESSION, Identifier.withDefaultNamespace("librarian"));
			for (int i = 0; i < 6; i++) {
				Villager villager = EntityTypes.VILLAGER.spawn(level, ref.bell().offset(56 + i, 0, 8), EntitySpawnReason.COMMAND);
				if (villager == null) throw new IllegalStateException("Unable to spawn staffing fixture");
				fixtures.add(villager);
				villager.addTag("vp090_fixture");
				villager.setNoAi(true);
				villager.setVillagerData(villager.getVillagerData().withProfession(level.registryAccess(), librarian).withLevel(2));
				villager.getBrain().setMemory(MemoryModuleType.MEETING_POINT,
					net.minecraft.core.GlobalPos.of(level.dimension(), ref.bell()));
			}
			Villager selected = fixtures.getFirst();
			VillagerMemory memory = selected.getAttachedOrCreate(VillagerPlus.MEMORY);
			long now = level.getGameTime();
			if (!Staffing.reassignIdle(level, ref, selected)) fail("idle villager takes temporary urgent job");
			var assignment = selected.getAttached(VillagerPlus.TEMPORARY_JOB);
			check(assignment != null && assignment.original().equals("minecraft:librarian")
				&& assignment.target().equals("minecraft:farmer") && assignment.until() == now + 24000L,
				"emergency assignment records original job, target and one-day return time");
			var saved = de.villagerplus.village.TemporaryAssignment.CODEC.encodeStart(JsonOps.INSTANCE, assignment).getOrThrow();
			var restored = de.villagerplus.village.TemporaryAssignment.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
			check(restored.equals(assignment), "temporary assignment survives its persistent attachment codec round trip");
			check(!Staffing.restoreTemporary(level, selected, memory, now + 23999L),
				"emergency assignment remains active before its return time");
			check(Staffing.restoreTemporary(level, selected, memory, now + 24000L)
				&& selected.getVillagerData().profession().is(librarian)
				&& selected.getVillagerData().level() == 2
				&& selected.getAttached(VillagerPlus.TEMPORARY_JOB) == null,
				"temporary job restores the original profession and level");
			return 4;
		} finally {
			for (Villager villager : fixtures) {
				villager.discard();
				Villages.forget(villager.getUUID());
			}
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) fail(message);
	}

	private static void fail(String message) {
		throw new IllegalStateException("CHECK FAILED: " + message);
	}
}
