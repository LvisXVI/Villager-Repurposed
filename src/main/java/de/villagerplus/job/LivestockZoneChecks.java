package de.villagerplus.job;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.ZonePolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Exercises real breeding and feed consumption against live pasture edits in the remote dev fixture. */
public final class LivestockZoneChecks {
	private LivestockZoneChecks() { }

	public static int run(ServerLevel level, VillageRef ref) {
		List<ZonePolicy.Zone> oldZones = ref.bellEntity().getAttached(VillagerPlus.ZONES);
		Optional<BlockPos> oldPen = ref.data().extra.pen;
		long oldCare = ref.data().extra.nextCare;
		Container box = ref.chest(level);
		List<ItemStack> oldStock = new ArrayList<>();
		for (int i = 0; i < box.getContainerSize(); i++) oldStock.add(box.getItem(i).copy());
		List<Entity> spawned = new ArrayList<>();
		BlockPos pen = ref.bell().offset(-38, 0, 38);
		level.getChunkAt(pen);
		int checks = 0;
		try {
			ref.data().extra.pen = Optional.of(pen);
			Villager worker = EntityTypes.VILLAGER.spawn(level, pen, EntitySpawnReason.COMMAND);
			if (worker == null) throw new IllegalStateException("livestock worker did not spawn");
			worker.setNoAi(true); worker.setInvulnerable(true); spawned.add(worker);
			Ctx ctx = new Ctx(level, worker, JobManager.state(worker), new VillagerMemory(), ref, level.getGameTime() + 1);
			Animal first = cow(level, pen, -.5, .5, spawned), second = cow(level, pen, 1.5, .5, spawned);

			ref.bellEntity().removeAttached(VillagerPlus.ZONES);
			prepare(ref, box, first, second);
			check(Livestock.tick(ctx) && first.isInLove() && second.isInLove() && wheat(box) == 18,
				"a world without pasture zones retains real two-feed breeding at its registered pen"); checks++;

			zones(ref, List.of(zone("housing", pen, 2)));
			prepare(ref, box, first, second);
			check(Livestock.tick(ctx) && first.isInLove() && second.isInLove() && wheat(box) == 18,
				"unrelated zone categories preserve the legacy livestock area"); checks++;

			zones(ref, List.of(new ZonePolicy.Zone("partial", "pasture", pen.getX() - 2, pen.getZ() - 2,
				pen.getX(), pen.getZ() + 2)));
			prepare(ref, box, first, second);
			check(!Livestock.tick(ctx) && !first.isInLove() && !second.isInLove() && wheat(box) == 20,
				"a pasture with only one adult cannot feed an unzoned second adult"); checks++;

			second.teleportTo(pen.getX() + 1.1, pen.getY(), pen.getZ() + .5);
			prepare(ref, box, first, second);
			check(!Livestock.tick(ctx) && !second.isInLove() && wheat(box) == 20,
				"an animal whose bounding box crosses the pasture edge remains outside by its center"); checks++;

			second.teleportTo(pen.getX() + 1.5, pen.getY(), pen.getZ() + .5);
			List<Animal> neighbors = new ArrayList<>();
			for (int i = 0; i < Livestock.PEN_CAPACITY_PER_SPECIES; i++) neighbors.add(cow(level, pen, 4.5, 4.5, spawned));
			zones(ref, List.of(zone("pasture", pen, 2)));
			prepare(ref, box, first, second);
			check(Livestock.tick(ctx) && first.isInLove() && second.isInLove() && wheat(box) == 18
				&& neighbors.stream().noneMatch(Animal::isInLove),
				"neighboring unzoned cattle are neither fed nor counted toward this pasture's breeding capacity"); checks++;
			neighbors.forEach(Entity::discard);

			zones(ref, List.of(zone("pasture", ref.bell().offset(50, 0, 50), 2)));
			prepare(ref, box, first, second);
			check(!Livestock.tick(ctx) && wheat(box) == 20 && !first.isInLove() && !second.isInLove(),
				"moving the pasture away stops a previously eligible pen without consuming food"); checks++;

			zones(ref, List.of(zone("pasture", ref.bell().east(80), 2)));
			prepare(ref, box, first, second);
			check(Livestock.tick(ctx) && first.isInLove() && second.isInLove() && wheat(box) == 18,
				"malformed persisted pasture bounds are ignored consistently with other zone planners"); checks++;

			zones(ref, List.of(zone("pasture", pen, 12)));
			second.teleportTo(pen.getX() + 8.5, pen.getY(), pen.getZ() + .5);
			prepare(ref, box, first, second);
			check(!Livestock.tick(ctx) && wheat(box) == 20 && !first.isInLove() && !second.isInLove(),
				"a large pasture cannot expand the registered pen's care radius to a distant herd"); checks++;
			return checks;
		} finally {
			spawned.forEach(Entity::discard);
			for (int i = 0; i < box.getContainerSize(); i++) box.setItem(i, oldStock.get(i));
			ref.data().extra.pen = oldPen;
			ref.data().extra.nextCare = oldCare;
			if (oldZones == null) ref.bellEntity().removeAttached(VillagerPlus.ZONES);
			else ref.bellEntity().setAttached(VillagerPlus.ZONES, oldZones);
			ref.dirty();
		}
	}

	private static Animal cow(ServerLevel level, BlockPos pen, double x, double z, List<Entity> spawned) {
		Animal animal = EntityTypes.COW.spawn(level, pen, EntitySpawnReason.COMMAND);
		if (animal == null) throw new IllegalStateException("livestock cow did not spawn");
		animal.setNoAi(true); animal.setInvulnerable(true); animal.setAge(0);
		animal.teleportTo(pen.getX() + x, pen.getY(), pen.getZ() + z);
		spawned.add(animal); return animal;
	}

	private static void prepare(VillageRef ref, Container box, Animal... animals) {
		ref.data().extra.nextCare = 0;
		for (int i = 0; i < box.getContainerSize(); i++) box.setItem(i, ItemStack.EMPTY);
		box.setItem(0, new ItemStack(Items.WHEAT, 20));
		for (Animal animal : animals) animal.resetLove();
	}
	private static int wheat(Container box) { return JobUtil.count(box, s -> s.is(Items.WHEAT)); }
	private static void zones(VillageRef ref, List<ZonePolicy.Zone> zones) { ref.bellEntity().setAttached(VillagerPlus.ZONES, zones); }
	private static ZonePolicy.Zone zone(String kind, BlockPos center, int radius) {
		return new ZonePolicy.Zone("livestock-zone-check", kind, center.getX() - radius, center.getZ() - radius,
			center.getX() + radius, center.getZ() + radius);
	}
	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("LIVESTOCK ZONE CHECK FAILED: " + message);
	}
}
