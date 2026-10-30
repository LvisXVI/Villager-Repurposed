package de.villagerplus.village;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.job.JobManager;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.player.Player;

/**
 * Steckbrief eines Dorfbewohners fuer das Handelsmenue. Zeilenformat: {@code Label|Wert|Prozent}
 * (Prozent -1 = kein Balken).
 */
public final class VillagerInfo {
	private VillagerInfo() {
	}

	public static List<String> lines(ServerLevel level, Villager v, Player player) {
		VillagerMemory mem = v.getAttachedOrCreate(VillagerPlus.MEMORY);
		Wellbeing.Status s = Wellbeing.of(level, v, mem);
		VillagerData data = v.getVillagerData();
		int rep = v.getPlayerReputation(player);

		List<String> out = new ArrayList<>();
		out.add("Name|" + (mem.name.isEmpty() ? "unbekannt" : mem.name) + "|-1");
		out.add("Beruf|" + Names.profession(data.profession()) + ", Stufe " + data.level() + "|-1");
		if (v.isBaby()) {
			out.add("Alter|Kind|-1");
		}
		out.add("Fertigkeit|" + de.villagerplus.job.Skills.title(de.villagerplus.job.Skills.level(mem)) + " (Stufe " + de.villagerplus.job.Skills.level(mem) + ")|" + de.villagerplus.job.Skills.progress(mem));
		String activity = JobManager.activityOf(v, level);
		String target = v.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET)
			.map(w -> w.getTarget().currentBlockPosition().toShortString()).orElse("kein Ziel");
		out.add("Tätigkeit|" + (activity == null ? "untätig" : activity) + " → " + target + "|-1");
		out.add("Stimmung|" + s.moodLabel() + " (" + s.mood() + "%)|" + s.mood());
		out.add("Sättigung|" + s.satiety() + "%|" + s.satiety());
		out.add("Energie|" + s.energy() + "%|" + s.energy());
		out.add("Soziales|" + s.social() + "%|" + s.social());
		out.add("Gesundheit|" + Math.round(v.getHealth()) + " / " + Math.round(v.getMaxHealth()) + "|" + s.health());
		out.add("Haus|" + (s.home() ? "ja" + (s.chest() ? ", mit eigener Kiste" : "") : "obdachlos") + "|-1");
		out.add("Privattruhe|" + privateChest(level, mem) + "|-1");
		out.add("Besitz|" + s.emeralds() + " Smaragde|-1");
		out.add("Ruf bei dir|" + (rep > 5 ? "gut" : rep < -5 ? "schlecht" : "neutral") + " (" + rep + ")|-1");
		out.add("Geschenke|" + mem.gifts.getOrDefault(player.getUUID(), 0) + " von dir|-1");
		out.add("Arbeit|" + mem.jobsDone + " erledigt|-1");
		out.add("Gespräche|" + mem.chats + "|-1");
		VillageRef vr = Villages.find(level, v);
		if (vr != null) {
			String wanted = vr.data().wanted.stream().limit(4).collect(Collectors.joining(", "));
			out.add("Fehlendes Baumaterial|" + (wanted.isEmpty() ? "keins" : wanted) + "|-1");
			String role = Market.role(vr,v); if (!role.isEmpty()) out.add("Marktrolle|"+role+"|-1");
			int pop = (int) level.getEntitiesOfClass(Villager.class, new net.minecraft.world.phys.AABB(vr.bell()).inflate(de.villagerplus.Config.VILLAGE_RADIUS), x -> x.isAlive() && !Guests.isGuest(x)).size();
			int score = Wealth.score(level, vr, pop);
			out.add("Dorfreichtum|" + score + " (Stufe " + Wealth.tier(score) + ")|-1");
		}
		out.add("Preise|" + priceText(Wellbeing.priceModifier(s)) + "|-1");
		return out;
	}

	private static String privateChest(ServerLevel level, VillagerMemory memory) {
		if (memory.homeChest.isEmpty()) return "nicht gefunden";
		var be = level.getBlockEntity(memory.homeChest.get());
		if (!(be instanceof net.minecraft.world.Container chest)) return "nicht geladen";
		List<String> contents = new ArrayList<>();
		for (int i = 0; i < chest.getContainerSize(); i++) {
			var stack = chest.getItem(i);
			if (!stack.isEmpty()) contents.add(stack.getCount() + "× " + stack.getHoverName().getString());
			if (contents.size() == 4) break;
		}
		return contents.isEmpty() ? "leer" : String.join(", ", contents);
	}

	private static String priceText(double mod) {
		if (mod < 0) {
			return "Rabatt wegen guter Laune";
		} else if (mod > 0) {
			return "Aufschlag wegen schlechter Laune";
		}
		return "normal";
	}
}
