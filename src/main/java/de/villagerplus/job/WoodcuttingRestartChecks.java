package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.VillagerMemory;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

/** A true process-restart fixture for the live woodcutting cursor, deliberately separate from the unit checks. */
public final class WoodcuttingRestartChecks {
	private static final String PHASE = "restart:woodcut:phase";
	private static final String UUID_MOST = "restart:woodcut:most";
	private static final String UUID_LEAST = "restart:woodcut:least";
	private static final String WORKER_POS = "restart:woodcut:worker_pos";
	private static final String ROOT = "restart:woodcut:root";
	private static final String LOG = "restart:woodcut:log";
	private static final String STAGE = "restart:woodcut:stage";
	private static final String EXPECTED_LOGS = "restart:woodcut:expected_logs";
	private static final String WAS_FORCED = "restart:woodcut:was_forced";
	private static final int PARTIAL_STAGE = 4;

	private WoodcuttingRestartChecks() {}

	/** Build a real oak tree, drive Woodcutting.tick to stage 4, and leave the entity/chunk for server restart. */
	public static int prepare(ServerLevel level, BlockPos at) {
		level.getChunkAt(at);
		var oldBell = level.getBlockEntity(at);
		check(oldBell == null || oldBell.getAttachedOrElse(VillagerPlus.ROADS, Map.of()).getOrDefault(PHASE, 0L) != 1,
			"an unfinished woodcut restart fixture must be verified before it can be replaced");

		VillageRef ref = Checks090.fixtureForEquipment(level, at);
		BlockPos root = at.offset(6, 0, 6);
		BlockPos workerPos = root.west();
		BlockPos log = root.above(4);
		long chunkKey = new ChunkPos(at.getX() >> 4, at.getZ() >> 4).pack();
		boolean wasForced = level.getChunkSource().getForceLoadedChunks().contains(chunkKey);
		Villager worker = null;
		try {
			// Keep the entire fixture, bell and villager inside one dedicated remote chunk.
			for (int dx = 4; dx <= 10; dx++) {
				for (int dz = 4; dz <= 10; dz++) {
					BlockPos p = at.offset(dx, 0, dz);
					level.setBlock(p.below(2), Blocks.STONE.defaultBlockState(), 3);
					level.setBlock(p.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
					for (int y = 0; y <= 6; y++) level.setBlock(p.above(y), Blocks.AIR.defaultBlockState(), 3);
				}
			}
			for (int y = 0; y < 5; y++) level.setBlock(root.above(y), Blocks.OAK_LOG.defaultBlockState(), 3);
			level.setBlock(root.offset(1, 4, 0), Blocks.OAK_LEAVES.defaultBlockState(), 3);

			VillageData data = ref.data();
			data.wanted.clear();
			data.wanted.add("minecraft:oak_log#1");
			ref.dirty();

			worker = EntityTypes.VILLAGER.spawn(level, workerPos, EntitySpawnReason.COMMAND);
			check(worker != null, "named woodcut test villager spawns");
			worker.setNoAi(true);
			worker.setInvulnerable(true);
			worker.setPersistenceRequired();
			worker.addTag("vp090_woodcut_restart");
			worker.setCustomName(Component.literal("VP090 Woodcut Restart"));
			worker.setVillagerData(worker.getVillagerData().withProfession(level.registryAccess(), VillagerProfession.NITWIT));
			worker.setAttached(VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty().start(root, "minecraft:oak"));

			JobState state = JobManager.state(worker);
			state.cooldownUntil = level.getGameTime() + 24_000;
			long now = level.getGameTime() + 1_000;
			int calls = 0;
			while (worker.getAttached(VillagerPlus.WOODCUTTER_WORK).stage() < PARTIAL_STAGE && calls < 8) {
				Woodcutting.tick(context(level, ref, worker, state, now));
				now += 6;
				calls++;
			}
			WoodcutterWork savedWork = worker.getAttached(VillagerPlus.WOODCUTTER_WORK);
			check(savedWork.root().orElseThrow().equals(root) && savedWork.log().orElseThrow().equals(log)
				&& savedWork.stage() == PARTIAL_STAGE && !savedWork.removalPending(),
				"actual woodcutting reaches a saved partial stage on the intended trunk");
			check(level.getBlockState(root).is(Blocks.OAK_LOG) && level.getBlockState(log).is(Blocks.OAK_LOG)
				&& logs(worker) == 0,
				"partial work leaves the real root and upper log intact without granting drops");
			check(Anim.breakingStage(worker) == PARTIAL_STAGE, "the live crack animation agrees with the persistent stage before shutdown");

			Map<String, Long> saved = copy(ref.bellEntity());
			saved.put(PHASE, 1L);
			saved.put(UUID_MOST, worker.getUUID().getMostSignificantBits());
			saved.put(UUID_LEAST, worker.getUUID().getLeastSignificantBits());
			saved.put(WORKER_POS, worker.blockPosition().asLong());
			saved.put(ROOT, root.asLong());
			saved.put(LOG, log.asLong());
			saved.put(STAGE, (long) PARTIAL_STAGE);
			saved.put(WAS_FORCED, wasForced ? 1L : 0L);
			ref.bellEntity().setAttached(VillagerPlus.ROADS, Map.copyOf(saved));
			ref.dirty();
			level.setChunkForced(at.getX() >> 4, at.getZ() >> 4, true);
			return calls + 4;
		} catch (RuntimeException failure) {
			if (worker != null) {
				Anim.cancelBreaking(worker);
				worker.discard();
			}
			restoreChunkTicket(level, at, wasForced);
			throw failure;
		}
	}

	/** Must run after a real server restart. Continues the live job and checks that its real drops commit once. */
	public static int verify(ServerLevel level, BlockPos at) {
		level.getChunkAt(at);
		var bell = level.getBlockEntity(at);
		check(bell != null, "saved woodcut fixture bell reloads");
		Map<String, Long> saved = bell.getAttached(VillagerPlus.ROADS);
		check(saved != null, "woodcut restart manifest survives with the fixture bell");
		long phase = saved.getOrDefault(PHASE, 0L);
		if (phase != 1L && phase != 2L) {
			restoreChunkTicket(level, at, saved.getOrDefault(WAS_FORCED, 0L) == 1L);
			throw new IllegalStateException("Woodcutting restart: prepare must precede a real restart verification");
		}
		if (!(saved.containsKey(ROOT) && saved.containsKey(LOG) && saved.containsKey(WORKER_POS)
			&& saved.containsKey(UUID_MOST) && saved.containsKey(UUID_LEAST))) {
			restoreChunkTicket(level, at, saved.getOrDefault(WAS_FORCED, 0L) == 1L);
			throw new IllegalStateException("Woodcutting restart: restart manifest contains the worker and stump coordinates");
		}
		BlockPos root = BlockPos.of(saved.getOrDefault(ROOT, Long.MIN_VALUE));
		BlockPos log = BlockPos.of(saved.getOrDefault(LOG, Long.MIN_VALUE));
		BlockPos workerPos = BlockPos.of(saved.getOrDefault(WORKER_POS, Long.MIN_VALUE));
		UUID id = new UUID(saved.getOrDefault(UUID_MOST, 0L), saved.getOrDefault(UUID_LEAST, 0L));
		level.getChunkAt(workerPos);
		Villager worker = level.getEntity(id) instanceof Villager found ? found : null;
		if (worker == null) {
			restoreChunkTicket(level, at, saved.getOrDefault(WAS_FORCED, 0L) == 1L);
			throw new IllegalStateException("Woodcutting restart: the same UUID woodcut worker is present after restart");
		}

		if (phase == 2) {
			try {
				check(logs(worker) == saved.getOrDefault(EXPECTED_LOGS, -1L), "repeat verify observes the same carried log count");
				check(level.getBlockState(log).isAir() && level.getBlockState(root).is(Blocks.OAK_LOG)
					&& level.getBlockState(root.above(3)).is(Blocks.OAK_LOG),
					"repeat verify does not resume the cursor or remove another real log");
				WoodcutterWork work = worker.getAttached(VillagerPlus.WOODCUTTER_WORK);
				check(work != null && !work.removalPending() && work.pendingDrops().isEmpty(),
					"repeat verify finds no pending output claim to replay");
				return 2;
			} finally {
				Anim.cancelBreaking(worker);
				restoreChunkTicket(level, at, saved.getOrDefault(WAS_FORCED, 0L) == 1L);
			}
		}
		int checks = 0;
		try {
			check(worker.blockPosition().asLong() == saved.getOrDefault(WORKER_POS, Long.MIN_VALUE),
				"the worker's exact persisted position survives restart"); checks++;
			VillageRef ref = new VillageRef(at, bell.getAttachedOrCreate(VillagerPlus.VILLAGE), bell);
			WoodcutterWork work = worker.getAttached(VillagerPlus.WOODCUTTER_WORK);
			check(work != null && work.root().orElseThrow().equals(root) && work.log().orElseThrow().equals(log)
				&& work.stage() == saved.getOrDefault(STAGE, -1L) && !work.removalPending(),
				"root, remaining target, and stage 4 attachment survive the real reload"); checks++;
			check(Anim.breakingStage(worker) == -1 && level.getBlockState(root).is(Blocks.OAK_LOG)
				&& level.getBlockState(log).is(Blocks.OAK_LOG) && logs(worker) == 0,
				"transient cracks reset while all uncommitted blocks and inventory remain unchanged"); checks++;

			long expectedLogs = dropCount(level, worker, log);
			check(expectedLogs > 0, "vanilla loot evaluation produces a real oak log drop for this axe and block"); checks++;
			JobState state = JobManager.state(worker);
			state.cooldownUntil = level.getGameTime() + 24_000;
			long now = level.getGameTime() + 1_000;
			int guard = 0;
			while (work.stage() < 9 && guard++ < 12) {
				Woodcutting.tick(context(level, ref, worker, state, now));
				now += 6;
				work = worker.getAttached(VillagerPlus.WOODCUTTER_WORK);
			}
			check(work.stage() == 9 && !work.removalPending() && level.getBlockState(log).is(Blocks.OAK_LOG),
				"resumed visible strokes finish at stage 9 without removing the log early"); checks++;

			Woodcutting.tick(context(level, ref, worker, state, now)); now += 6;
			work = worker.getAttached(VillagerPlus.WOODCUTTER_WORK);
			check(work.removalPending() && !work.pendingDrops().isEmpty() && level.getBlockState(log).is(Blocks.OAK_LOG),
				"the real drop list is persistently claimed before the block is removed"); checks++;
			check(dropCount(work.pendingDrops(), Items.OAK_LOG) == expectedLogs,
				"pending output exactly matches vanilla drops from the target block"); checks++;

			Woodcutting.tick(context(level, ref, worker, state, now)); now += 6;
			check(level.getBlockState(log).isAir() && logs(worker) == 0,
				"claimed target is removed before any output is delivered"); checks++;
			Woodcutting.tick(context(level, ref, worker, state, now)); now += 6;
			work = worker.getAttached(VillagerPlus.WOODCUTTER_WORK);
			check(!work.removalPending() && work.pendingDrops().isEmpty() && logs(worker) == expectedLogs,
				"real pending output is delivered exactly once into the worker inventory"); checks++;

			// Start the next connected trunk segment once; a retry must not replay the previous block's output.
			Woodcutting.tick(context(level, ref, worker, state, now));
			check(logs(worker) == expectedLogs && level.getBlockState(root.above(3)).is(Blocks.OAK_LOG)
				&& !worker.getAttached(VillagerPlus.WOODCUTTER_WORK).removalPending(),
				"continuation advances to the next stump log without duplicating the prior drop"); checks++;

			Map<String, Long> complete = copy(bell);
			complete.put(PHASE, 2L);
			complete.put(EXPECTED_LOGS, expectedLogs);
			bell.setAttached(VillagerPlus.ROADS, Map.copyOf(complete));
			bell.setChanged();
			return checks;
		} finally {
			Anim.cancelBreaking(worker);
			restoreChunkTicket(level, at, saved.getOrDefault(WAS_FORCED, 0L) == 1L);
		}
	}

	private static Ctx context(ServerLevel level, VillageRef ref, Villager worker, JobState state, long now) {
		VillagerMemory memory = worker.getAttachedOrCreate(VillagerPlus.MEMORY);
		return new Ctx(level, worker, state, memory, ref, now);
	}

	private static long dropCount(ServerLevel level, Villager worker, BlockPos log) {
		return dropCount(Block.getDrops(level.getBlockState(log), level, log, null, worker, new ItemStack(Items.IRON_AXE)), Items.OAK_LOG);
	}

	private static long dropCount(List<ItemStack> drops, net.minecraft.world.item.Item item) {
		return drops.stream().filter(stack -> stack.is(item)).mapToLong(ItemStack::getCount).sum();
	}

	private static int logs(Villager worker) {
		return (int) JobUtil.count(worker.getInventory(), stack -> stack.is(Items.OAK_LOG));
	}

	private static Map<String, Long> copy(BlockEntity bell) {
		Map<String, Long> current = bell.getAttachedOrElse(VillagerPlus.ROADS, Map.of());
		return new HashMap<>(current);
	}

	private static void restoreChunkTicket(ServerLevel level, BlockPos at, boolean wasForced) {
		if (wasForced) return;
		level.setChunkForced(at.getX() >> 4, at.getZ() >> 4, false);
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("Woodcutting restart: " + message);
	}
}
