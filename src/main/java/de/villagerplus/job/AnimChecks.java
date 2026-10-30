package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.village.VillageRef;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Deterministic animation checks for the explicitly gated development runner; no real-time waiting. */
public final class AnimChecks {
	private AnimChecks() {}

	public static int run(ServerLevel level, VillageRef ref) {
		BlockPos first = ref.bell().west(14).above(4);
		BlockPos second = first.north();
		var oldFirst = level.getBlockState(first);
		var oldSecond = level.getBlockState(second);
		var villagerType = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(
			net.minecraft.resources.Identifier.parse("minecraft:villager"));
		Villager v = (Villager) villagerType.spawn(level, first.east(2), EntitySpawnReason.COMMAND);
		if (v == null) throw new IllegalStateException("ANIMATION CHECK FAILED: cannot spawn fixture");
		v.addTag("vp090_fixture");
		v.setNoAi(true);
		JobState st = new JobState();
		st.activity = "animation fixture";
		long now = level.getGameTime();
		int checks = 0;
		try {
			level.setBlock(first, Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(second, Blocks.STONE.defaultBlockState(), 3);
			var stone = level.getBlockState(first);
			for (int stage = 0; stage <= 9; stage++) {
				Ctx c = context(level, ref, v, st, now + stage * 6L);
				check(!Anim.breakStep(c, first, stone, Items.IRON_PICKAXE) && Anim.breakingStage(v) == stage,
					"separate stroke broadcasts crack stage " + stage); checks++;
				check(!Anim.breakStep(c, first, stone, Items.IRON_PICKAXE) && Anim.breakingStage(v) == stage,
					"same-tick duplicate cannot advance stage " + stage); checks++;
			}
			check(v.getMainHandItem().is(Items.IRON_PICKAXE), "tool is equipped before animation"); checks++;
			check(Anim.breakStep(context(level, ref, v, st, now + 60), first, stone, Items.IRON_PICKAXE)
				&& Anim.breakingStage(v) == -1, "completion follows visible stage nine and clears progress"); checks++;
			check(level.getBlockState(first) == stone, "animation does not mutate or duplicate block drops"); checks++;
			Anim.breakStep(context(level, ref, v, st, now + 66), first, stone, Items.IRON_PICKAXE);
			Anim.cancelBreaking(v);
			check(Anim.breakingStage(v) == -1, "explicit abort clears progress"); checks++;
			Anim.breakStep(context(level, ref, v, st, now + 72), first, stone, Items.IRON_PICKAXE);
			Anim.breakStep(context(level, ref, v, st, now + 78), first, stone, Items.IRON_PICKAXE);
			Anim.breakStep(context(level, ref, v, st, now + 84), second, stone, Items.IRON_PICKAXE);
			check(Anim.breakingStage(v) == 0, "retarget starts a fresh crack sequence"); checks++;
			level.setBlock(second, Blocks.AIR.defaultBlockState(), 3);
			Anim.maintainBreaking(level);
			check(Anim.breakingStage(v) == -1, "externally removed target clears progress"); checks++;
			Anim.breakStep(context(level, ref, v, st, now), first, stone, Items.IRON_PICKAXE);
			st.cooldownUntil = now + 600;
			Anim.maintainBreaking(level);
			check(Anim.breakingStage(v) == -1, "task-cancel cooldown clears progress"); checks++;
			st.cooldownUntil = 0;
			Anim.breakStep(context(level, ref, v, st, now - 101), first, stone, Items.IRON_PICKAXE);
			Anim.maintainBreaking(level);
			check(Anim.breakingStage(v) == -1, "abandoned work expires without a later job call"); checks++;
			Anim.breakStep(context(level, ref, v, st, now), first, stone, Items.IRON_PICKAXE);
			st.activity = "another task";
			Anim.maintainBreaking(level);
			check(Anim.breakingStage(v) == -1, "activity switch clears progress"); checks++;
			Anim.breakStep(context(level, ref, v, st, now), first, stone, Items.IRON_PICKAXE);
			v.discard();
			Anim.maintainBreaking(level);
			check(Anim.breakingStage(v) == -1, "entity removal clears original breaker progress"); checks++;
			return checks;
		} finally {
			Anim.cancelBreaking(v);
			v.discard();
			level.setBlock(first, oldFirst, 3);
			level.setBlock(second, oldSecond, 3);
		}
	}

	private static Ctx context(ServerLevel level, VillageRef ref, Villager v, JobState st, long now) {
		return new Ctx(level, v, st, v.getAttachedOrCreate(VillagerPlus.MEMORY), ref, now);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("ANIMATION CHECK FAILED: " + message);
	}
}
