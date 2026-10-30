package de.villagerplus.dev;

import com.mojang.serialization.JsonOps;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Builder;
import de.villagerplus.build.Blueprints;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobManager;
import de.villagerplus.village.Development;
import de.villagerplus.village.VillageExtent;
import de.villagerplus.village.VillageRef;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Focused source-level assertions for stable village housing and remembered footprint behavior. */
public final class DevelopmentChecks {
	private DevelopmentChecks() {}

	/** Returns the number of passing checks; call from the explicitly gated Checks090 development runner. */
	public static int run(ServerLevel level, VillageRef ref) {
		int checks = 0;
		check(Development.needsHousing(2, 2) && Development.needsHousing(1, 2), "two-person hamlet reserves one house when beds are full or missing"); checks++;
		check(Development.needsHousing(3, 3) && Development.needsHousing(2, 3), "three-person hamlet reserves one house when beds are full or missing"); checks++;
		check(!Development.needsHousing(1, 0) && !Development.needsHousing(500, de.villagerplus.Config.MAX_VILLAGERS),
			"empty and capped villages do not queue housing"); checks++;
		var oldSpecials = ref.bellEntity().getAttached(VillagerPlus.SPECIALS);
		var oldSchool = ref.data().extra.school;
		try {
			ref.bellEntity().setAttached(VillagerPlus.SPECIALS, java.util.Map.of());
			ref.data().extra.school = java.util.Optional.empty();
			check(Builder.nextSpecial(ref, 1) == null, "specialized milestone queue waits until the village has two residents"); checks++;
			check(Blueprints.GRANARY.equals(Builder.nextSpecial(ref, 2)), "two-resident hamlet starts with the granary milestone"); checks++;
			de.villagerplus.village.Specials.set(ref, Blueprints.GRANARY, ref.bell().north(8));
			check(Blueprints.TOOLSHED.equals(Builder.nextSpecial(ref, 2)), "granary is followed by the tool shed even at two residents"); checks++;
			de.villagerplus.village.Specials.set(ref, Blueprints.TOOLSHED, ref.bell().north(16));
			check(Blueprints.SCHOOL.equals(Builder.nextSpecial(ref, 2)), "school follows the tool shed in a two-resident hamlet"); checks++;
			ref.data().extra.school = java.util.Optional.of(ref.bell().north(24));
			check(Blueprints.KITCHEN.equals(Builder.nextSpecial(ref, 2)), "kitchen follows school without a five-resident gate"); checks++;
			de.villagerplus.village.Specials.set(ref, Blueprints.KITCHEN, ref.bell().north(32));
			check(Builder.nextSpecial(ref, 2) == null, "late railway milestone stays gated after the core hamlet milestones"); checks++;
		} finally {
			if (oldSpecials == null) ref.bellEntity().removeAttached(VillagerPlus.SPECIALS);
			else ref.bellEntity().setAttached(VillagerPlus.SPECIALS, oldSpecials);
			ref.data().extra.school = oldSchool;
		}
		var villagerType = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.parse("minecraft:villager"));
		Villager firstBuilder = (Villager) villagerType.spawn(level, ref.bell().east(2), EntitySpawnReason.COMMAND);
		Villager secondBuilder = (Villager) villagerType.spawn(level, ref.bell().east(3), EntitySpawnReason.COMMAND);
		if (firstBuilder == null || secondBuilder == null) throw new IllegalStateException("DEVELOPMENT CHECK FAILED: unable to spawn builder fixtures");
		firstBuilder.addTag("vp090_fixture"); secondBuilder.addTag("vp090_fixture"); firstBuilder.setNoAi(true); secondBuilder.setNoAi(true);
		BlockPos adjacentBell = ref.bell().east(40);
		level.setBlock(adjacentBell.below(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(adjacentBell, net.minecraft.world.level.block.Blocks.BELL.defaultBlockState(), 3);
		var adjacentEntity = (net.minecraft.world.level.block.entity.BellBlockEntity) level.getBlockEntity(adjacentBell);
		var adjacentData = new de.villagerplus.village.VillageData();
		adjacentEntity.setAttached(VillagerPlus.VILLAGE, adjacentData);
		VillageRef adjacentRef = new VillageRef(adjacentBell, adjacentData, adjacentEntity);
		Villager awayMason = (Villager) villagerType.spawn(level, adjacentBell.east(2), EntitySpawnReason.COMMAND);
		if (awayMason == null) throw new IllegalStateException("DEVELOPMENT CHECK FAILED: unable to spawn away mason fixture");
		awayMason.addTag("vp090_fixture"); awayMason.addTag(de.villagerplus.village.Expedition.TAG); awayMason.setNoAi(true);
		var masonProfession = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.VILLAGER_PROFESSION,
			net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "mason"));
		awayMason.setVillagerData(awayMason.getVillagerData().withProfession(level.registryAccess(), masonProfession).withLevel(2));
		de.villagerplus.village.Villages.noteChunk(level.getChunkAt(adjacentBell));
		// Prime both entries, then emulate vanilla changing their meeting point before the cache TTL expires.
		de.villagerplus.village.Villages.find(level, firstBuilder);
		de.villagerplus.village.Villages.find(level, secondBuilder);
		firstBuilder.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.MEETING_POINT,
			net.minecraft.core.GlobalPos.of(level.dimension(), ref.bell()));
		secondBuilder.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.MEETING_POINT,
			net.minecraft.core.GlobalPos.of(level.dimension(), adjacentBell));
		awayMason.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.MEETING_POINT,
			net.minecraft.core.GlobalPos.of(level.dimension(), adjacentBell));
		var firstAssignment = de.villagerplus.village.Villages.find(level, firstBuilder);
		var secondAssignment = de.villagerplus.village.Villages.find(level, secondBuilder);
		var awayAssignment = de.villagerplus.village.Villages.find(level, awayMason);
		check(firstAssignment != null && firstAssignment.bell().equals(ref.bell())
			&& secondAssignment != null && secondAssignment.bell().equals(adjacentRef.bell())
			&& awayAssignment != null && awayAssignment.bell().equals(adjacentRef.bell()),
			"meeting-point changes invalidate cached village assignments"); checks++;
		var firstResidents = de.villagerplus.village.Villages.residents(level, ref, 96, v -> v.entityTags().contains("vp090_fixture"));
		var secondResidents = de.villagerplus.village.Villages.residents(level, adjacentRef, 96, v -> v.entityTags().contains("vp090_fixture"));
		var firstIds = firstResidents.stream().map(Villager::getUUID).collect(java.util.stream.Collectors.toSet());
		var secondIds = secondResidents.stream().map(Villager::getUUID).collect(java.util.stream.Collectors.toSet());
		String assignments = java.util.stream.Stream.of(firstBuilder, secondBuilder, awayMason).map(v -> {
			var assigned = de.villagerplus.village.Villages.find(level, v);
			return v.getUUID() + "@" + v.blockPosition() + "->" + (assigned == null ? "none" : assigned.bell())
				+ " meeting=" + v.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.MEETING_POINT).orElse(null);
		}).collect(java.util.stream.Collectors.joining("; "));
		check(firstIds.equals(java.util.Set.of(firstBuilder.getUUID()))
			&& secondIds.equals(java.util.Set.of(secondBuilder.getUUID(), awayMason.getUUID()))
			&& java.util.Collections.disjoint(firstIds, secondIds),
			"overlapping village radii keep residents assigned to their own bell (A=" + firstResidents.size()
				+ ", B=" + secondResidents.size() + "; IDs A=" + firstIds + ", B=" + secondIds + "; " + assignments + ")"); checks++;
		long now = level.getGameTime();
		boolean firstElected = Builder.bootstrapBuilder(new Ctx(level, firstBuilder, JobManager.state(firstBuilder), firstBuilder.getAttachedOrCreate(VillagerPlus.MEMORY), ref, now));
		boolean secondElected = Builder.bootstrapBuilder(new Ctx(level, secondBuilder, JobManager.state(secondBuilder), secondBuilder.getAttachedOrCreate(VillagerPlus.MEMORY), adjacentRef, now));
		check(firstElected && secondElected, "villages elect an autonomous builder even when their only mason is away on expedition"); checks++;
		firstBuilder.discard(); secondBuilder.discard(); awayMason.discard();
		de.villagerplus.village.Villages.forget(firstBuilder.getUUID()); de.villagerplus.village.Villages.forget(secondBuilder.getUUID()); de.villagerplus.village.Villages.forget(awayMason.getUUID());
		level.setBlock(adjacentBell, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
		level.setBlock(adjacentBell.below(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
		check(Development.needsHousing(21, 21), "stable population fills beds and queues one reserve house"); checks++;
		check(!Development.needsHousing(22, 21), "housing reserve prevents unnecessary house spam"); checks++;
		check(Development.ownsVillageStart(new BoundingBox(-20, 40, -20, 20, 90, 20), BlockPos.ZERO),
			"structure start containing bell is accepted"); checks++;
		check(!Development.ownsVillageStart(new BoundingBox(-20, 40, -20, 20, 90, 20), new BlockPos(21, 40, 0)),
			"adjacent independent structure start is excluded"); checks++;

		var oldMines = new java.util.ArrayList<>(ref.data().extra.oldMines);
		var oldMine = ref.data().mine;
		BlockPos exhaustedEntrance = ref.bell().offset(44, 0, 42);
		level.getChunkAt(exhaustedEntrance);
		level.setBlock(exhaustedEntrance.below(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
		de.villagerplus.village.VillageData.Mine exhausted = new de.villagerplus.village.VillageData.Mine(exhaustedEntrance, net.minecraft.core.Direction.EAST.get2DDataValue(), 20,
			exhaustedEntrance.getY() - 20, true);
		try {
			ref.data().extra.oldMines.add(exhausted);
			ref.data().mine = java.util.Optional.empty();
			de.villagerplus.job.Mining.barricade(level, exhausted, true);
			BlockPos signPos = exhaustedEntrance.above(1).west();
			var sign = level.getBlockEntity(signPos);
			check(sign instanceof net.minecraft.world.level.block.entity.SignBlockEntity,
				"exhausted mine receives a visible sign at the barricaded entrance"); checks++;
			if (sign instanceof net.minecraft.world.level.block.entity.SignBlockEntity signEntity) {
				var signText = signEntity.getFrontText();
				check(signText.getMessage(0, false).getString().equals("[VERSCHLOSSEN")
					&& signText.getMessage(1, false).getString().equals("- ERSCHÖPFT]")
					&& signText.getMessage(3, false).getString().equals("Alte Mine") && signEntity.isWaxed(),
					"exhausted mine sign preserves exact status, map label and waxed state"); checks++;
			}
			int[] mapOrigin = de.villagerplus.village.VillageMap.gridOrigin(level, ref);
			int cellSize = de.villagerplus.village.VillageMap.gridCellSize(level, ref);
			int col = Math.floorDiv(exhaustedEntrance.getX() - mapOrigin[0], cellSize);
			int row = Math.floorDiv(exhaustedEntrance.getZ() - mapOrigin[1], cellSize);
			byte[] mapMarks = de.villagerplus.village.VillageMap.grid(level, ref)[1];
			check(col >= 0 && col < de.villagerplus.village.VillageMap.GRID && row >= 0 && row < de.villagerplus.village.VillageMap.GRID
				&& mapMarks[row * de.villagerplus.village.VillageMap.GRID + col] == 8,
				"exhausted mine remains visible as old-mine marker on village map"); checks++;
		} finally {
			ref.data().extra.oldMines.clear();
			ref.data().extra.oldMines.addAll(oldMines);
			ref.data().mine = oldMine;
			ref.dirty();
		}

		VillageExtent original = new VillageExtent(-10, 40, -8, 12, 90, 9);
		BoundingBox extra = new BoundingBox(-30, 35, 5, 18, 100, 40);
		VillageExtent union = original.union(extra);
		check(union.minX() == -30 && union.minY() == 35 && union.minZ() == -8
			&& union.maxX() == 18 && union.maxY() == 100 && union.maxZ() == 40,
			"remembered footprint union only expands to cover both bounds"); checks++;
		VillageExtent decoded = VillageExtent.CODEC.parse(JsonOps.INSTANCE,
			VillageExtent.CODEC.encodeStart(JsonOps.INSTANCE, union).getOrThrow()).getOrThrow();
		check(decoded.equals(union), "remembered village bounds survive codec round trip"); checks++;

		VillageExtent old = ref.bellEntity().getAttachedOrElse(VillagerPlus.VILLAGE_EXTENT, null);
		try {
			VillageExtent remembered = new VillageExtent(ref.bell().getX() - 500, ref.bell().getY() - 5, ref.bell().getZ() - 400,
				ref.bell().getX() + 500, ref.bell().getY() + 50, ref.bell().getZ() + 400);
			ref.bellEntity().setAttached(VillagerPlus.VILLAGE_EXTENT, remembered);
			BoundingBox afterScan = Development.bounds(level, ref);
			check(afterScan.minX() <= remembered.minX() && afterScan.minZ() <= remembered.minZ()
				&& afterScan.maxX() >= remembered.maxX() && afterScan.maxZ() >= remembered.maxZ(),
				"bounds retain previously discovered extent across unload/restart scans"); checks++;
		} finally {
			if (old == null) ref.bellEntity().removeAttached(VillagerPlus.VILLAGE_EXTENT);
			else ref.bellEntity().setAttached(VillagerPlus.VILLAGE_EXTENT, old);
		}
		return checks;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("DEVELOPMENT CHECK FAILED: " + message);
	}
}
