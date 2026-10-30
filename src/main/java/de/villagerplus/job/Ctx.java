package de.villagerplus.job;

import de.villagerplus.VillagerMemory;
import de.villagerplus.village.VillageRef;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;

/** Alles, was ein Job pro Aufruf braucht. {@code village} ist null, wenn der Dorfbewohner keine Glocke kennt. */
public record Ctx(ServerLevel level, Villager v, JobState st, VillagerMemory mem, VillageRef village, long now) {
	public boolean idle(int slot) {
		return now < st.idleUntil[slot];
	}

	public void setIdle(int slot, long ticks) {
		st.idleUntil[slot] = now + ticks;
	}

	public void setActivity(String text) {
		st.activity = text;
	}
}
