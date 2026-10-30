package de.villagerplus.job;

import de.villagerplus.Config;
import de.villagerplus.VillagerPlus;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.ZonePolicy;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/**
 * Tierhaltung: Im Gehege des Dorfes fuettern Dorfbewohner Kuehe, Schafe, Schweine und Huehner mit passendem Futter aus
 * der Dorfkiste, damit sie sich vermehren. Der Metzger haelt den Bestand klein, der Schaefer schert die Schafe.
 */
public final class Livestock {
	/** Per-species working capacity. Breeding pauses at capacity; the butcher may remove only unprotected excess. */
	public static final int PEN_CAPACITY_PER_SPECIES = Config.CULL_CAP;
	private Livestock() {
	}

	private record Species(EntityType<? extends Animal> type, Item food, String name) { }

	private static final List<Species> SPECIES = List.of(
		new Species(EntityTypes.COW, Items.WHEAT, "Kühe"),
		new Species(EntityTypes.SHEEP, Items.WHEAT, "Schafe"),
		new Species(EntityTypes.PIG, Items.CARROT, "Schweine"),
		new Species(EntityTypes.CHICKEN, Items.WHEAT_SEEDS, "Hühner"));

	public static boolean canBreed(int adults, int total, int availableFeed) {
		return adults >= 2 && total < PEN_CAPACITY_PER_SPECIES && availableFeed >= 2;
	}

	/** true = beschaeftigt. */
	public static boolean tick(Ctx c) {
		VillageRef ref = c.village();
		if (ref == null || ref.data().extra.pen.isEmpty()) {
			return false;
		}
		if (c.now() < ref.data().extra.nextCare) {
			return false;
		}
		ServerLevel level = c.level();
		Container box = ref.chest(level);
		if (box == null) {
			return false;
		}
		BlockPos pen = ref.data().extra.pen.get();
		AABB area = new AABB(pen).inflate(5, 3, 5);
		// A drawn pasture restricts this pen; it never expands its query into another settlement's herd.
		// Re-read on every care tick so a zone edit also applies while the worker is approaching an animal.
		List<ZonePolicy.Zone> pastures = ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES, List.of()).stream()
			.filter(z -> "pasture".equals(z.kind()) && ZonePolicy.validBounds(ref, z.kind(), z.minX(), z.minZ(), z.maxX(), z.maxZ()))
			.limit(ZonePolicy.MAX_ZONES).toList();
		for (Species sp : SPECIES) {
			List<Animal> adults = level.getEntitiesOfClass(Animal.class, area,
				a -> a.isAlive() && !a.isBaby() && a.getType() == sp.type() && !a.isInLove() && inPasture(a, area, pastures));
			long total = level.getEntitiesOfClass(Animal.class, area,
				a -> a.isAlive() && a.getType() == sp.type() && inPasture(a, area, pastures)).size();
			int availableFeed = JobUtil.count(box, s -> s.is(sp.food()));
			if (!canBreed(adults.size(), (int) total, availableFeed)) {
				continue;
			}
			Animal first = adults.get(0);
			c.setActivity("füttert " + sp.name());
			Anim.hold(c, sp.food());
			if (!JobUtil.approach(c, first.position(), 3.6)) {
				return true;
			}
			if (JobUtil.take(box, s -> s.is(sp.food()), 2) != 2) {
				return false;
			}
			for (int i = 0; i < 2; i++) {
				Animal a = adults.get(i);
				a.setInLove(null);
				level.sendParticles(ParticleTypes.HEART, a.getX(), a.getY() + 1.0, a.getZ(), 3, 0.3, 0.3, 0.3, 0.0);
			}
			JobUtil.swing(c.v());
			c.mem().jobsDone++;
			c.st().cooldownUntil = c.now() + 60;
			ref.data().extra.nextCare = c.now() + 400;
			ref.dirty();
			return true;
		}
		ref.data().extra.nextCare = c.now() + 600;
		return false;
	}

	private static boolean inPasture(Animal animal, AABB pen, List<ZonePolicy.Zone> pastures) {
		if (pastures.isEmpty()) return true;
		// Entity queries use bounding-box intersection. Use the animal's center for parcel membership so
		// a cow leaning across a fence is not counted or fed from the neighboring parcel.
		return pen.contains(animal.position()) && pastures.stream().anyMatch(z -> animal.getX() >= z.minX()
			&& animal.getX() < (double)z.maxX() + 1 && animal.getZ() >= z.minZ() && animal.getZ() < (double)z.maxZ() + 1);
	}
}
