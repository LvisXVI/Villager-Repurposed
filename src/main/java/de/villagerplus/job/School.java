package de.villagerplus.job;

import de.villagerplus.Config;
import de.villagerplus.VillagerMemory;
import de.villagerplus.village.VillageRef;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LecternBlock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Comparator;

/**
 * Kindheit: Kinder wachsen langsamer auf ({@code Config.GROWTH_FACTOR}) und gehen tagsueber in die Dorfschule. Dort lernen
 * sie; jede Unterrichtsstunde zaehlt als Erfahrung, mit der sie spaeter in ihren Beruf starten (kleiner Vorsprung, siehe Skills).
 */
public final class School {
	static boolean isSchoolActivity(String activity) {
		return activity != null && activity.startsWith("lernt ");
	}
	private static final int LESSON_TICKS = 200;

	private School() {
	}

	/** Bremst das Aufwachsen: bei jedem Takt wird ein Teil der vergangenen Zeit wieder vom Alter abgezogen. */
	public static void slowGrowth(Villager v) {
		int age = v.getAge();
		if (age < 0 && Config.GROWTH_FACTOR > 1) {
			int back = (int) (Config.JOB_INTERVAL * (1.0 - 1.0 / Config.GROWTH_FACTOR));
			v.setAge(age - back);
		}
	}

	// --- Lehrer und Lectern -----------------------------------------------------------------

	private static final Map<Long, BlockPos> LECTERNS = new HashMap<>();
	private static final Map<Long, Long> TRIED = new HashMap<>();
	private static final Map<Long, Object[]> TEACHERS = new HashMap<>(); // Glocke -> {Zeit, UUID, Name}
	private static final Map<Long, Long> TAUGHT = new HashMap<>(); // Glocke -> letzte Unterrichtsminute (Spielzeit)
	private static final Map<Long, Long> JOURNAL_DAY = new HashMap<>();

	/** Lehrerpult der Schule (gesucht um den Ursprung, bei Altbestand nachtraeglich gesetzt). */
	public static BlockPos lectern(ServerLevel level, BlockPos school) {
		BlockPos c = LECTERNS.get(school.asLong());
		if (c != null && level.getBlockState(c).getBlock() instanceof LecternBlock) {
			return c;
		}
		LECTERNS.remove(school.asLong());
		if (!level.hasChunkAt(school) || level.getGameTime() < TRIED.getOrDefault(school.asLong(), 0L)) {
			return null;
		}
		TRIED.put(school.asLong(), level.getGameTime() + 2400);
		var box = new net.minecraft.world.level.levelgen.structure.BoundingBox(school.getX() - 7, school.getY() - 1, school.getZ() - 7, school.getX() + 7, school.getY() + 5, school.getZ() + 7);
		if (de.villagerplus.build.Furnisher.placeLectern(level, box)) {
			for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
				if (level.getBlockState(pos).getBlock() instanceof LecternBlock) {
					LECTERNS.put(school.asLong(), pos.immutable());
					return pos.immutable();
				}
			}
		}
		return null;
	}

	/** Der Lehrer des Dorfes: Bibliothekar (Lectern ist sein Arbeitsblock), sonst Kleriker; bei mehreren der mit der kleinsten UUID. Alle 5 s neu bestimmt. */
	private static Object[] teacher(ServerLevel level, VillageRef ref) {
		Object[] t = TEACHERS.get(ref.bell().asLong());
		if (t != null && level.getGameTime() - (Long) t[0] < 100) {
			return t;
		}
		Villager best = null;
		for (String job : new String[] {"librarian", "cleric"}) {
			best = level.getEntitiesOfClass(Villager.class, new net.minecraft.world.phys.AABB(ref.bell()).inflate(Config.VILLAGE_RADIUS),
				v -> v.isAlive() && !v.isBaby() && !v.isSleeping() && !de.villagerplus.village.Guests.isGuest(v) && !de.villagerplus.village.Expedition.isAway(v)
					&& v.getVillagerData().profession().unwrapKey().map(k -> k.identifier().getPath().contains(job)).orElse(false))
				.stream().min(Comparator.comparing(Villager::getUUID)).orElse(null);
			if (best != null) {
				break;
			}
		}
		UUID id = best == null ? null : best.getUUID();
		String name = best == null ? "" : best.getAttachedOrCreate(de.villagerplus.VillagerPlus.MEMORY).name;
		t = new Object[] {level.getGameTime(), id, name};
		TEACHERS.put(ref.bell().asLong(), t);
		return t;
	}

	/** Findet gerade Unterricht statt (Lehrer hat in den letzten 20 Sekunden am Pult unterrichtet)? */
	public static boolean taught(ServerLevel level, VillageRef ref) {
		return level.getGameTime() - TAUGHT.getOrDefault(ref.bell().asLong(), -1000L) < 400;
	}

	/** Arbeitszeit des Lehrers: am Pult stehen und die Kinder unterrichten (true = beschaeftigt). Ohne Kinder oder Pult arbeitet er normal. */
	public static boolean teach(Ctx c, Phase phase) {
		VillageRef ref = c.village();
		if (ref == null || ref.data().extra.school.isEmpty() || phase.routine) {
			return false;
		}
		Object[] t = teacher(c.level(), ref);
		if (t[1] == null || !t[1].equals(c.v().getUUID())) {
			return false;
		}
		BlockPos lectern = lectern(c.level(), ref.data().extra.school.get());
		if (lectern == null) {
			return false;
		}
		boolean kids = !c.level().getEntitiesOfClass(Villager.class, new net.minecraft.world.phys.AABB(lectern).inflate(Config.VILLAGE_RADIUS), v -> v.isBaby() && v.isAlive() && !v.isSleeping()).isEmpty();
		if (!kids) {
			return false;
		}
		Direction f = c.level().getBlockState(lectern).getValue(LecternBlock.FACING);
		c.setActivity("unterrichtet die Kinder am Lehrerpult");
		Anim.hold(c, Items.WRITTEN_BOOK);
		if (!JobUtil.approach(c, lectern.relative(f), 1.6)) {
			return true;
		}
		c.v().getLookControl().setLookAt(lectern.getX() + 0.5 + f.getStepX() * 4, c.v().getEyeY(), lectern.getZ() + 0.5 + f.getStepZ() * 4);
		if (c.now() >= c.st().cooldownUntil) {
			JobUtil.swing(c.v());
			c.mem().jobsDone++;
			c.st().cooldownUntil = c.now() + LESSON_TICKS;
			TAUGHT.put(ref.bell().asLong(), c.now());
			c.level().sendParticles(net.minecraft.core.particles.ParticleTypes.ENCHANT, lectern.getX() + 0.5, lectern.getY() + 1.4, lectern.getZ() + 0.5, 12, 0.3, 0.2, 0.3, 0.5);
			long day = c.now() / 24000L;
			if (!Long.valueOf(day).equals(JOURNAL_DAY.get(ref.bell().asLong()))) {
				JOURNAL_DAY.put(ref.bell().asLong(), day);
				de.villagerplus.village.Journal.add(c.level(), ref, "Befinden", c.mem().name, "unterrichtet die Dorfkinder in der Schule", 0);
			}
		}
		return true;
	}

	/** Tagsueber: Kind sitzt in der Schule vor dem Lehrerpult und lernt (true = beschaeftigt). Mit Lehrer 3 Erfahrung je Stunde, ohne nur 1. */
	public static boolean attend(Ctx c, Phase phase) {
		VillageRef ref = c.village();
		if (ref == null || ref.data().extra.school.isEmpty() || phase.routine) {
			return false;
		}
		BlockPos school = ref.data().extra.school.get();
		BlockPos lectern = lectern(c.level(), school);
		BlockPos seat = school;
		Direction f = null;
		if (lectern != null) {
			f = c.level().getBlockState(lectern).getValue(LecternBlock.FACING);
			int h = c.v().getUUID().hashCode();
			// Sitzreihen: 2-3 Bloecke vor dem Pult, seitlich versetzt je Kind
			seat = lectern.relative(f, 3 + Math.floorMod(h, 2)).relative(f.getClockWise(), Math.floorMod(h >>> 4, 5) - 2);
		}
		boolean teacherHere = taught(c.level(), ref);
		Object[] t = teacher(c.level(), ref);
		String apprenticeship = Apprentices.activity(c.level(), c.v());
		c.setActivity(apprenticeship != null ? apprenticeship : teacherHere ? "lernt beim Lehrer " + t[2] : "lernt in der Schule (Lehrer fehlt)");
		Anim.hold(c, Items.BOOK);
		if (!JobUtil.approach(c, seat, 1.8)) {
			return true;
		}
		if (lectern != null) {
			c.v().getLookControl().setLookAt(lectern.getX() + 0.5, c.v().getEyeY(), lectern.getZ() + 0.5);
		}
		VillagerMemory mem = c.mem();
		if (c.now() >= c.st().cooldownUntil) {
			int lessonXp = teacherHere ? 3 : 1;
			mem.jobsDone += lessonXp;
			Apprentices.awardLesson(c.v(), lessonXp);
			if (teacherHere) {
				JobUtil.swing(c.v());
				c.level().sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, c.v().getX(), c.v().getY() + 1.4, c.v().getZ(), 2, 0.2, 0.1, 0.2, 0.0);
			}
			c.st().cooldownUntil = c.now() + LESSON_TICKS;
		}
		return true;
	}
}
