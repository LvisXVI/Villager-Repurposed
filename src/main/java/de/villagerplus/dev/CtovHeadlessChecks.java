package de.villagerplus.dev;

import de.villagerplus.VillagerPlus;
import de.villagerplus.village.TradeNetwork;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Villages;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.phys.AABB;

/** Locates a generated CTOV/vanilla village and runs smoke checks without synthetic fixtures. */
public final class CtovHeadlessChecks {
	private CtovHeadlessChecks() { }

	public static final Path MARKER = Path.of("run/vp-ctov-headless.marker");

	public static VillageRef bootstrap(ServerLevel level) {
		BlockPos origin = level.getRespawnData().pos();
		BlockPos located = level.findNearestMapStructure(StructureTags.VILLAGE, origin, 512, false);
		if (located == null) throw new IllegalStateException("CTOV headless: no village structure within 512 blocks of spawn");
		int chunkX = located.getX() >> 4;
		int chunkZ = located.getZ() >> 4;
		for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) {
			level.setChunkForced(chunkX + dx, chunkZ + dz, true);
			level.getChunk(chunkX + dx, chunkZ + dz);
		}
		BellBlockEntity bell = findVillageBell(level, located, 96);
		if (bell == null) throw new IllegalStateException("CTOV headless: no bell near structure at " + located.toShortString());
		BlockPos bellPos = bell.getBlockPos();
		Villages.noteChunk(level.getChunkAt(bellPos));
		if (bell.getAttached(VillagerPlus.NETWORK) == null) bell.setAttached(VillagerPlus.NETWORK, TradeNetwork.create(bellPos));
		if (bell.getAttached(VillagerPlus.VILLAGE) == null) bell.setAttached(VillagerPlus.VILLAGE, new de.villagerplus.village.VillageData());
		return new VillageRef(bellPos, bell.getAttachedOrCreate(VillagerPlus.VILLAGE), bell);
	}

	public static int run(ServerLevel level, VillageRef ref) {
		int checks = 0;
		boolean ctovBlock = level.registryAccess().lookupOrThrow(Registries.BLOCK).listElementIds()
			.anyMatch(id -> id.identifier().getNamespace().equals("ctov") || id.identifier().getNamespace().equals("towns_and_towers"));
		check(ctovBlock, "CTOV or Towns and Towers blocks are registered"); checks++;
		int villagers = level.getEntitiesOfClass(Villager.class, new AABB(ref.bell()).inflate(48), Villager::isAlive).size();
		check(villagers >= 2, "natural village has at least two villagers nearby (found " + villagers + ")"); checks++;
		boolean delightPot = level.registryAccess().lookupOrThrow(Registries.BLOCK)
			.get(net.minecraft.resources.Identifier.parse("farmersdelight:cooking_pot")).isPresent();
		check(delightPot, "Farmer's Delight cooking pot block is registered in this dev pack"); checks++;
		try {
			Files.writeString(MARKER, ref.bell().getX() + "," + ref.bell().getY() + "," + ref.bell().getZ() + "\n");
		} catch (java.io.IOException io) {
			throw new IllegalStateException("CTOV headless: could not write marker", io);
		}
		System.out.println("CTOV HEADLESS PASS natural bell " + ref.bell().toShortString() + " villagers=" + villagers);
		return checks;
	}

	public static int verify(ServerLevel level) {
		if (!Files.isRegularFile(MARKER)) throw new IllegalStateException("CTOV verify: missing marker from prior ctov run");
		try {
			String[] parts = Files.readString(MARKER).trim().split(",");
			BlockPos bell = new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
			level.getChunkAt(bell);
			if (!(level.getBlockEntity(bell) instanceof BellBlockEntity be)) throw new IllegalStateException("CTOV verify: bell missing after restart");
			check(be.getAttached(VillagerPlus.VILLAGE) != null, "village attachment survived restart");
			System.out.println("CTOV HEADLESS PASS persistence after restart at " + bell.toShortString());
			return 1;
		} catch (java.io.IOException | NumberFormatException parse) {
			throw new IllegalStateException("CTOV verify: unreadable marker", parse);
		}
	}

	private static BellBlockEntity findVillageBell(ServerLevel level, BlockPos center, int radius) {
		BellBlockEntity best = null;
		int bestVillagers = -1;
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -8, -radius), center.offset(radius, 12, radius))) {
			if (!(level.getBlockEntity(pos) instanceof BellBlockEntity bell)) continue;
			int count = level.getEntitiesOfClass(Villager.class, new AABB(pos).inflate(32), Villager::isAlive).size();
			if (count > bestVillagers) {
				bestVillagers = count;
				best = bell;
			}
		}
		return best;
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("CTOV headless: " + message);
	}
}
