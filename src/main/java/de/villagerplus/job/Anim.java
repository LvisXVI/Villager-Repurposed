package de.villagerplus.job;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Animationen: Dorfbewohner zeigen das passende Werkzeug in der Hand (Hacke, Spitzhacke, Axt, Schere, Angel, Buch,
 * Essen ...), schwingen den Arm und erzeugen Partikel und Geraeusche. Das Werkzeug wird von {@code JobManager}
 * automatisch wieder weggesteckt.
 */
public final class Anim {
	private static final long HOLD_TICKS = 50;
	private static final int HIT_TICKS = 6;
	private static final long BREAK_IDLE_TICKS = 100;
	private static final java.util.Map<Villager, Breaking> BREAKING = new java.util.IdentityHashMap<>();
	private static boolean registered;

	private static final class Breaking {
		final Ctx context;
		final BlockPos pos;
		final BlockState state;
		final Item tool;
		final String activity;
		int stage;
		long advancedAt = Long.MIN_VALUE;
		long touchedAt;
		long swungAt;

		Breaking(Ctx c, BlockPos pos, BlockState state, Item tool, int resumeStage) {
			this.context = c;
			this.pos = pos.immutable();
			this.state = state;
			this.tool = tool;
			this.activity = c.st().activity;
			this.stage = Math.max(-1, Math.min(9, resumeStage));
			this.touchedAt = c.now();
			this.swungAt = c.now();
			this.advancedAt = this.stage < 0 ? Long.MIN_VALUE : c.now();
		}
	}

	private Anim() {
	}

	/** Zeigt {@code item} fuer kurze Zeit in der Hand (wird bei jeder Aktion verlaengert). */
	public static void hold(Ctx c, Item item) {
		c.st().tool = item;
		c.st().toolUntil = c.now() + HOLD_TICKS;
	}

	/** One paced work stroke: broadcasts every crack stage 0–9 before allowing removal on a later call. */
	public static boolean breakStep(Ctx c, BlockPos pos, BlockState state, Item tool) {
		return breakStep(c, pos, state, tool, -1);
	}

	/** Restores a persisted crack stage only when starting a fresh breaker for this exact target. */
	public static boolean breakStep(Ctx c, BlockPos pos, BlockState state, Item tool, int resumeStage) {
		registerBreaking();
		Breaking work = BREAKING.get(c.v());
		if (work != null && (work.context.level() != c.level() || !work.pos.equals(pos) || work.state != state
			|| work.tool != tool || !work.activity.equals(c.st().activity) || c.now() - work.touchedAt > BREAK_IDLE_TICKS)) {
			cancelBreaking(c.v());
			work = null;
		}
		if (state.isAir() || !c.level().hasChunkAt(pos) || c.level().getBlockState(pos) != state) {
			cancelBreaking(c.v());
			return false;
		}
		if (work == null) {
			work = new Breaking(c, pos, state, tool, resumeStage);
			BREAKING.put(c.v(), work);
		}
		work.touchedAt = c.now();
		hold(c, tool);
		// Equipment must reach the client before the first swing packet.
		apply(c.v(), c.st(), c.now());
		if (work.stage >= 0 && c.now() - work.advancedAt < HIT_TICKS) return false;
		if (work.stage == 9) {
			cancelBreaking(c.v());
			return true;
		}
		work.stage++;
		work.advancedAt = c.now();
		stroke(work, c.now());
		c.level().destroyBlockProgress(c.v().getId(), pos, work.stage);
		return false;
	}

	/** Clears the packet under the original breaker id, including when a job switches targets. */
	public static void cancelBreaking(Villager v) {
		Breaking work = BREAKING.remove(v);
		if (work != null) work.context.level().destroyBlockProgress(v.getId(), work.pos, -1);
	}

	public static int breakingStage(Villager v) {
		Breaking work = BREAKING.get(v);
		return work == null ? -1 : work.stage;
	}

	private static void stroke(Breaking work, long now) {
		Villager v = work.context.v();
		v.getLookControl().setLookAt(work.pos.getX() + 0.5, work.pos.getY() + 0.5, work.pos.getZ() + 0.5);
		JobUtil.swing(v);
		work.swungAt = now;
		work.context.level().playSound(null, work.pos, work.state.getSoundType().getHitSound(),
			net.minecraft.sounds.SoundSource.BLOCKS, 0.6F, 0.9F);
	}

	// Runs independently of job scheduling: pauses, cancellation and unload must not leave ghost cracks.
	static void maintainBreaking(ServerLevel level) {
		long now = level.getGameTime();
		for (Breaking work : java.util.List.copyOf(BREAKING.values())) {
			if (work.context.level() != level) continue;
			Villager v = work.context.v();
			JobState st = work.context.st();
			Phase phase = Phase.of(level.getOverworldClockTime());
			if (v.isRemoved() || !v.isAlive() || v.isSleeping() || v.isTrading() || phase.routine || phase == Phase.NIGHT
				|| level.isThundering() || !level.hasChunkAt(work.pos) || level.getBlockState(work.pos) != work.state
				|| now - work.touchedAt > BREAK_IDLE_TICKS || st.cooldownUntil > now + BREAK_IDLE_TICKS
				|| !work.activity.equals(st.activity) || st.tool != work.tool) {
				cancelBreaking(v);
				continue;
			}
			st.toolUntil = now + HOLD_TICKS;
			apply(v, st, now);
			if (now - work.swungAt >= HIT_TICKS) stroke(work, now);
		}
	}

	private static void registerBreaking() {
		if (registered) return;
		registered = true;
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_LEVEL_TICK.register(Anim::maintainBreaking);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof Villager v) cancelBreaking(v);
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (Villager v : java.util.List.copyOf(BREAKING.keySet())) cancelBreaking(v);
		});
	}

	/** Wird vom JobManager in jedem Takt aufgerufen: Hand mit dem gewuenschten Gegenstand abgleichen. */
	public static void apply(Villager v, JobState st, long now) {
		Item desired = st.tool != null && now < st.toolUntil ? st.tool : null;
		ItemStack current = v.getItemBySlot(EquipmentSlot.MAINHAND);
		if (desired != null) {
			if (!current.is(desired)) {
				v.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(desired));
			}
			st.holding = true;
		} else if (st.holding) {
			v.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
			st.holding = false;
			st.tool = null;
		}
	}

	/** Brockelnde Block-Partikel + Geraeusch (wie beim Abbauen). */
	public static void blockBreak(ServerLevel level, BlockPos pos, BlockState state) {
		level.levelEvent(2001, pos, Block.getId(state));
	}

	/** Essens-Partikel vor dem Gesicht. */
	public static void eatParticles(ServerLevel level, Villager v, Item food) {
		level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, food), v.getX(), v.getY() + 1.5, v.getZ(), 6, 0.15, 0.1, 0.15, 0.05);
	}
}
