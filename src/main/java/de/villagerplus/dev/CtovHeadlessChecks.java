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

	public static Path marker(net.minecraft.server.MinecraftServer server) {
		Path directory = server.getServerDirectory();
		if (directory == null) directory = Path.of(".");
		return directory.resolve("vp-ctov-headless.marker");
	}

	public static VillageRef bootstrap(ServerLevel level) {
		BlockPos origin = level.getRespawnData().pos();
		BlockPos located = nearestCtovVillage(level, origin);
		if (located == null) throw new IllegalStateException("CTOV headless: no ctov structure within search radius of spawn");
		int chunkX = located.getX() >> 4;
		int chunkZ = located.getZ() >> 4;
		for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) {
			level.setChunkForced(chunkX + dx, chunkZ + dz, true);
			level.getChunk(chunkX + dx, chunkZ + dz);
		}
		for (int i = 0; i < 40; i++) level.tick(() -> false);
		BellBlockEntity bell = findVillageBell(level, located, 8);
		if (bell == null) throw new IllegalStateException("CTOV headless: no bell near structure at " + located.toShortString());
		BlockPos bellPos = bell.getBlockPos();
		Villages.noteChunk(level.getChunkAt(bellPos));
		if (bell.getAttached(VillagerPlus.NETWORK) == null) bell.setAttached(VillagerPlus.NETWORK, TradeNetwork.create(bellPos));
		if (bell.getAttached(VillagerPlus.VILLAGE) == null) bell.setAttached(VillagerPlus.VILLAGE, new de.villagerplus.village.VillageData());
		return new VillageRef(bellPos, bell.getAttachedOrCreate(VillagerPlus.VILLAGE), bell);
	}

	public static int run(ServerLevel level, VillageRef ref) {
		int checks = 0;
		boolean ctovMod = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("ctov");
		var villageStart = level.structureManager().getStructureWithPieceAt(ref.bell(), StructureTags.VILLAGE);
		var structureKey = villageStart != null && villageStart.isValid()
			? level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getResourceKey(villageStart.getStructure())
			: java.util.Optional.<net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.structure.Structure>>empty();
		boolean ctovStructure = structureKey.map(key -> key.identifier().getNamespace().equals("ctov")).orElse(false);
		check(ctovMod && ctovStructure, "bell stands inside a generated CTOV village structure"
			+ structureKey.map(key -> " (" + key.identifier() + ")").orElse("")); checks++;
		int villagers = level.getEntitiesOfClass(Villager.class, new AABB(ref.bell()).inflate(48), Villager::isAlive).size();
		check(villagers >= 2, "natural village has at least two villagers nearby (found " + villagers + ")"); checks++;
		boolean delightPot = level.registryAccess().lookupOrThrow(Registries.BLOCK)
			.get(net.minecraft.resources.Identifier.parse("farmersdelight:cooking_pot")).isPresent();
		check(delightPot, "Farmer's Delight cooking pot block is registered in this dev pack"); checks++;
		try {
			Path marker = marker(level.getServer());
			if (marker.getParent() != null) Files.createDirectories(marker.getParent());
			Files.writeString(marker, ref.bell().getX() + "," + ref.bell().getY() + "," + ref.bell().getZ() + "\n");
		} catch (java.io.IOException io) {
			throw new IllegalStateException("CTOV headless: could not write marker", io);
		}
		var data = ref.bellEntity().getAttachedOrCreate(VillagerPlus.VILLAGE);
		data.planType = "ctov-headless";
		ref.bellEntity().setAttached(VillagerPlus.VILLAGE, data);
		ref.bellEntity().setChanged();
		level.getChunkAt(ref.bell()).markUnsaved();
		System.out.println("CTOV HEADLESS PASS natural bell " + ref.bell().toShortString() + " villagers=" + villagers);
		level.getServer().saveEverything(true, true, true);
		return checks;
	}

	public static int verify(ServerLevel level) {
		Path marker = marker(level.getServer());
		if (!Files.isRegularFile(marker)) throw new IllegalStateException("CTOV verify: missing marker from prior ctov run");
		try {
			String[] parts = Files.readString(marker).trim().split(",");
			BlockPos bell = new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
			level.getChunkAt(bell);
			if (!(level.getBlockEntity(bell) instanceof BellBlockEntity be)) throw new IllegalStateException("CTOV verify: bell missing after restart");
			check(be.getAttached(VillagerPlus.VILLAGE) instanceof de.villagerplus.village.VillageData saved
				&& "ctov-headless".equals(saved.planType), "village attachment survived restart");
			System.out.println("CTOV HEADLESS PASS persistence after restart at " + bell.toShortString());
			return 1;
		} catch (java.io.IOException | NumberFormatException parse) {
			throw new IllegalStateException("CTOV verify: unreadable marker", parse);
		}
	}

	private static BlockPos nearestCtovVillage(ServerLevel level, BlockPos origin) {
		var lookup = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		java.util.List<net.minecraft.core.Holder<net.minecraft.world.level.levelgen.structure.Structure>> ctov = new java.util.ArrayList<>();
		lookup.listElements().forEach(holder -> {
			if (holder.key().identifier().getNamespace().equals("ctov")) ctov.add(holder);
		});
		if (ctov.isEmpty()) return null;
		var found = level.getChunkSource().getGenerator().findNearestMapStructure(level,
			net.minecraft.core.HolderSet.direct(ctov), origin, 64, false);
		return found == null ? null : found.getFirst();
	}

	private static BellBlockEntity findVillageBell(ServerLevel level, BlockPos center, int radiusChunks) {
		BellBlockEntity best = null;
		int bestVillagers = -1;
		int cx = center.getX() >> 4;
		int cz = center.getZ() >> 4;
		for (int dx = -radiusChunks; dx <= radiusChunks; dx++) for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
			var chunk = level.getChunk(cx + dx, cz + dz);
			for (var blockEntity : chunk.getBlockEntities().values()) {
				if (!(blockEntity instanceof BellBlockEntity bell)) continue;
				int count = level.getEntitiesOfClass(Villager.class, new AABB(bell.getBlockPos()).inflate(32), Villager::isAlive).size();
				if (count > bestVillagers) {
					bestVillagers = count;
					best = bell;
				}
			}
		}
		return best;
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("CTOV headless: " + message);
	}
}
