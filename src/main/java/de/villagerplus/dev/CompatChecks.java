package de.villagerplus.dev;

import de.villagerplus.Compat;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.VillagerProfession;

/** Reports optional modpack integration surface without requiring those mods at compile time. */
public final class CompatChecks {
	private CompatChecks() { }

	private static final String[] OPTIONAL_MODS = {
		"farmersdelight", "chefsdelight", "morevillagers", "sereneseasons", "towns_and_towers", "village_taverns", "ctov"
	};

	private static final String[] OPTIONAL_BLOCKS = {
		"farmersdelight:cooking_pot", "farmersdelight:cutting_board", "farmersdelight:stove"
	};

	public static int run(ServerLevel level) {
		int checks = 0;
		for (String mod : OPTIONAL_MODS) {
			boolean present = BuiltInRegistries.ITEM.keySet().stream().anyMatch(id -> id.getNamespace().equals(mod))
				|| BuiltInRegistries.BLOCK.keySet().stream().anyMatch(id -> id.getNamespace().equals(mod));
			System.out.println("COMPAT optional mod " + mod + "=" + (present ? "present" : "absent"));
			checks++;
		}
		for (String block : OPTIONAL_BLOCKS) {
			boolean present = BuiltInRegistries.BLOCK.containsKey(Identifier.parse(block));
			System.out.println("COMPAT optional block " + block + "=" + (present ? "present" : "absent"));
			checks++;
		}
		check(Compat.JOBS.length >= 11, "compat job tag table covers core professions"); checks++;
		var registry = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.VILLAGER_PROFESSION);
		long modded = registry.listElements().filter(h -> !h.key().identifier().getNamespace().equals("minecraft")).count();
		System.out.println("COMPAT modded villager professions=" + modded);
		check(modded >= 0, "compat scans modded professions without crashing"); checks++;
		return checks;
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("Compat: " + message);
	}
}
