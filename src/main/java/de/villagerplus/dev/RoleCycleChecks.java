package de.villagerplus.dev;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.build.StationShed;
import de.villagerplus.job.CookJob;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.FarmerJob;
import de.villagerplus.job.FreightLoading;
import de.villagerplus.job.GravelQuarry;
import de.villagerplus.job.GuardDuty;
import de.villagerplus.job.HealerBrewing;
import de.villagerplus.job.HealerJob;
import de.villagerplus.job.JobManager;
import de.villagerplus.job.JobState;
import de.villagerplus.job.JobUtil;
import de.villagerplus.job.Lighting;
import de.villagerplus.job.PenExpansion;
import de.villagerplus.job.Workstation;
import de.villagerplus.village.RailTransport;
import de.villagerplus.village.Specials;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Wellbeing;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Headless world checks for the previously open job, depot and station behaviours. */
public final class RoleCycleChecks {
	private static int checks;

	private RoleCycleChecks() { }

	public static int run(ServerLevel level, BlockPos origin) {
		checks = 0;
		pace();
		lighting(level, origin);
		brewing(level, origin.south(48));
		guard(level, origin.east(48));
		pen(level, origin.south(96));
		freight(level, origin.east(96));
		gravel(level, origin.west(48));
		workstation(level, origin.west(96));
		station(level, origin.north(48));
		sign(level, origin.north(96));
		return checks;
	}

	private static void pace() {
		var hungry = new Wellbeing.Status(10, 80, 80, 100, 80, 0, true, true);
		var fed = new Wellbeing.Status(90, 90, 90, 100, 80, 0, true, true);
		check(Wellbeing.workQuarters(hungry, false) == 2, "hunger directly caps a cheerful worker at half pace");
		check(Wellbeing.workQuarters(hungry, true) == 3, "a prepared meal restores one work quarter");
		check(Wellbeing.workQuarters(fed, false) == 4, "a fed worker keeps full pace");
		check(new CookJob().indoorsDuringStorm() && new HealerJob().indoorsDuringStorm(), "cook and healer keep indoor work in a storm");
		check(!new FarmerJob().indoorsDuringStorm(), "farmer stops outdoor work in a storm");
	}

	private static void lighting(ServerLevel level, BlockPos at) {
		List<Entity> spawned = new ArrayList<>();
		try {
			VillageRef ref = Checks090.fixtureForEquipment(level, at);
			BlockPos floor = at.above(2);
			column(level, floor);
			BlockPos air = floor.above();
			var villager = villager(level, air.north(), spawned);
			ref.chest(level).setItem(0, new net.minecraft.world.item.ItemStack(Items.TORCH, 2));
			BlockPos owned = at.west(2);
			column(level, owned.below());
			level.setBlock(owned, Blocks.CHEST.defaultBlockState(), 3);
			level.getBlockEntity(owned).setAttached(VillagerPlus.OWNER, UUID.randomUUID());
			var privateChest = net.minecraft.world.level.block.entity.HopperBlockEntity.getContainerAt(level, owned);
			privateChest.setItem(0, new net.minecraft.world.item.ItemStack(Items.TORCH, 4));
			check(Lighting.placeable(level, air), "open stone pad with block light 0 can take a lamp");
			JobState state = JobManager.state(villager);
			state.target = air;
			Ctx ctx = context(level, ref, villager, state);
			check(Lighting.work(ctx) && level.getBlockState(air).is(Blocks.TORCH), "lamplighter places one real torch");
			check(JobUtil.count(ref.chest(level), stack -> stack.is(Items.TORCH)) == 1, "communal torch stock decreases by one");
			check(JobUtil.count(privateChest, stack -> stack.is(Items.TORCH)) == 4, "private chest torches stay untouched");
			check(!Lighting.placeable(level, air), "a placed torch is no longer a dark cell");
			level.setBlock(air.east(), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(air.east().below(), Blocks.FARMLAND.defaultBlockState(), 3);
			check(!Lighting.placeable(level, air.east()), "farmland is not a lamp site");
		} finally {
			spawned.forEach(Entity::discard);
		}
	}

	private static void brewing(ServerLevel level, BlockPos at) {
		List<Entity> spawned = new ArrayList<>();
		try {
			VillageRef ref = Checks090.fixtureForEquipment(level, at);
			BlockPos stand = at.north(2);
			column(level, stand.below());
			level.setBlock(stand, Blocks.BREWING_STAND.defaultBlockState(), 3);
			var chest = ref.chest(level);
			chest.setItem(0, PotionContents.createItemStack(Items.POTION, Potions.WATER));
			chest.setItem(1, new net.minecraft.world.item.ItemStack(Items.NETHER_WART));
			chest.setItem(2, new net.minecraft.world.item.ItemStack(Items.BLAZE_POWDER));
			chest.setItem(3, new net.minecraft.world.item.ItemStack(Items.GLISTERING_MELON_SLICE));
			BlockPos owned = at.west(2);
			column(level, owned.below());
			level.setBlock(owned, Blocks.CHEST.defaultBlockState(), 3);
			level.getBlockEntity(owned).setAttached(VillagerPlus.OWNER, UUID.randomUUID());
			var privateChest = net.minecraft.world.level.block.entity.HopperBlockEntity.getContainerAt(level, owned);
			privateChest.setItem(0, new net.minecraft.world.item.ItemStack(Items.NETHER_WART, 3));
			var healer = villager(level, stand.east(), spawned);
			Ctx ctx = context(level, ref, healer, JobManager.state(healer));
			for (int i = 0; i < 4; i++) HealerBrewing.work(ctx);
			var entity = level.getBlockEntity(stand);
			check(entity instanceof BrewingStandBlockEntity, "brewing stand block entity exists");
			BrewingStandBlockEntity brewing = (BrewingStandBlockEntity) entity;
			check(HealerBrewing.potionPath(brewing.getItem(0)).equals("water"), "water bottle moved into the stand");
			check(brewing.getItem(HealerBrewing.INGREDIENT).is(Items.NETHER_WART), "nether wart moved into the stand");
			check(brewing.getItem(HealerBrewing.FUEL).is(Items.BLAZE_POWDER), "blaze powder moved into the stand");
			check(JobUtil.count(privateChest, stack -> stack.is(Items.NETHER_WART)) == 3, "private brewing ingredients stay private");
			HealerBrewing.tickStand(level, stand, 450);
			check(HealerBrewing.potionPath(brewing.getItem(0)).equals("awkward"), "vanilla brewing stand turns water and nether wart into awkward potion");
			check(brewing.getItem(HealerBrewing.INGREDIENT).isEmpty(), "brewing consumes the nether wart");
			for (int i = 0; i < 2; i++) HealerBrewing.work(ctx);
			HealerBrewing.tickStand(level, stand, 450);
			check(HealerJob.isHealingPotion(brewing.getItem(0)) || HealerJob.isHealingPotion(healer.getInventory().getItem(0)),
				"glistering melon finishes a healing potion");
			for (int i = 0; i < 2 && !HealerJob.isHealingPotion(healer.getInventory().getItem(0)); i++) HealerBrewing.work(ctx);
			check(HealerJob.isHealingPotion(healer.getInventory().getItem(0)), "healer takes the finished potion out of the stand");
			var patient = villager(level, stand.west(), spawned);
			patient.setInvulnerable(false);
			patient.setHealth(4.0F);
			healer.teleportTo(patient.getX() + 1.0, patient.getY(), patient.getZ());
			float before = patient.getHealth();
			JobState healState = JobManager.state(healer);
			healState.clearTarget();
			healState.idleUntil[0] = 0;
			healState.idleUntil[1] = 0;
			healState.cooldownUntil = 0;
			healState.targetEntity = patient;
			new HealerJob().primary(context(level, ref, healer, healState));
			check(patient.getHealth() > before, "healer applies the brewed potion to a hurt villager; health "
				+ before + " -> " + patient.getHealth() + " activity=" + healState.activity
				+ " carried=" + healer.getInventory().getItem(0));
		} finally {
			spawned.forEach(Entity::discard);
		}
	}

	private static void guard(ServerLevel level, BlockPos at) {
		List<Entity> spawned = new ArrayList<>();
		try {
			VillageRef ref = Checks090.fixtureForEquipment(level, at);
			BlockPos post = at.north(4).above(6);
			column(level, post.below());
			level.setBlock(post, Blocks.OAK_FENCE.defaultBlockState(), 3);
			level.setBlock(post.above(), Blocks.LANTERN.defaultBlockState(), 3);
			BlockPos station = at.east(6);
			column(level, station.below());
			Specials.set(ref, Blueprints.STATION, station);
			var posts = GuardDuty.posts(level, ref);
			check(posts.stream().anyMatch(point -> point.getX() == post.getX() && point.getZ() == post.getZ()), "guard posts include the lantern tower");
			check(posts.stream().anyMatch(point -> point.distSqr(station) <= 16), "guard posts include the station");
			check(!GuardDuty.raidNearby(level, at), "no raid is reported for an empty sky platform");
			var guard = villager(level, at.above(), spawned);
			var zombie = EntityTypes.ZOMBIE.spawn(level, at.east().above(), EntitySpawnReason.COMMAND);
			spawned.add(zombie);
			zombie.setNoAi(true);
			zombie.teleportTo(at.east().getX() + 0.5, at.above().getY(), at.east().getZ() + 0.5);
			JobState state = JobManager.state(guard);
			state.lightAt = level.getGameTime() + 1000;
			float health = zombie.getHealth();
			GuardDuty.work(context(level, ref, guard, state));
			check(zombie.getHealth() < health, "guard hurts a hostile at contact range");
			check(state.activity.contains("bekämpft"), "guard reports the fight");
		} finally {
			spawned.forEach(Entity::discard);
		}
	}

	private static void pen(ServerLevel level, BlockPos at) {
		List<Entity> spawned = new ArrayList<>();
		try {
			VillageRef ref = Checks090.fixtureForEquipment(level, at);
			ref.data().extra.pen = Optional.of(at);
			for (int i = 0; i < 9; i++) {
				var chicken = EntityTypes.CHICKEN.spawn(level, at.above(), EntitySpawnReason.COMMAND);
				spawned.add(chicken);
				chicken.setNoAi(true);
				chicken.teleportTo(at.getX() + 0.5, at.above().getY(), at.getZ() + 0.5);
			}
			check(PenExpansion.overCapacity(level, at), "nine chickens exceed the pen cap");
			for (int i = 0; i < 3; i++) column(level, at.east(6).south(i).below());
			ref.chest(level).setItem(0, new net.minecraft.world.item.ItemStack(Items.OAK_FENCE, 3));
			BlockPos owned = at.west(2);
			column(level, owned.below());
			level.setBlock(owned, Blocks.CHEST.defaultBlockState(), 3);
			level.getBlockEntity(owned).setAttached(VillagerPlus.OWNER, UUID.randomUUID());
			var privateChest = net.minecraft.world.level.block.entity.HopperBlockEntity.getContainerAt(level, owned);
			privateChest.setItem(0, new net.minecraft.world.item.ItemStack(Items.OAK_FENCE, 6));
			var butcher = villager(level, at.east(5).above(), spawned);
			check(PenExpansion.work(context(level, ref, butcher, JobManager.state(butcher))), "butcher extends the stall");
			int fences = 0;
			for (int i = 0; i < 3; i++) if (level.getBlockState(at.east(6).south(i)).is(Blocks.OAK_FENCE)) fences++;
			check(fences == 3 && JobUtil.count(ref.chest(level), stack -> stack.is(Items.OAK_FENCE)) == 0, "three communal fences become the new stall");
			check(JobUtil.count(privateChest, stack -> stack.is(Items.OAK_FENCE)) == 6, "private fences are not used for the stall");
		} finally {
			spawned.forEach(Entity::discard);
		}
	}

	private static void freight(ServerLevel level, BlockPos at) {
		List<Entity> spawned = new ArrayList<>();
		try {
			VillageRef ref = Checks090.fixtureForEquipment(level, at);
			BlockPos rail = at.south(2);
			column(level, rail.below());
			level.setBlock(rail, Blocks.RAIL.defaultBlockState(), 3);
			Specials.set(ref, Blueprints.STATION, at);
			var cart = new MinecartChest(EntityTypes.CHEST_MINECART, level);
			spawned.add(cart);
			cart.setPos(rail.getX() + 0.5, rail.getY(), rail.getZ() + 0.5);
			level.addFreshEntity(cart);
			BlockPos other = at.east(40);
			cart.setAttached(RailTransport.TRIP, new RailTransport.Trip(at, other, rail, other, false, true, false, 0, 0));
			var chest = ref.chest(level);
			chest.setItem(0, new net.minecraft.world.item.ItemStack(Items.WHEAT, 20));
			chest.setItem(1, new net.minecraft.world.item.ItemStack(Items.EMERALD, 5));
			var porter = villager(level, rail.east(), spawned);
			Ctx ctx = context(level, ref, porter, JobManager.state(porter));
			check(!FreightLoading.work(ctx) && JobUtil.count(chest, stack -> stack.is(Items.WHEAT)) == 20, "a travelling minecart is not loaded");
			cart.setAttached(RailTransport.TRIP, new RailTransport.Trip(at, other, rail, other, false, false, true, 0, 0));
			check(FreightLoading.work(ctx), "porter loads a docked chest minecart");
			check(JobUtil.count(cart, stack -> stack.is(Items.WHEAT)) == 16, "sixteen wheat move into the cart");
			check(JobUtil.count(chest, stack -> stack.is(Items.WHEAT)) == 4 && JobUtil.count(chest, stack -> stack.is(Items.EMERALD)) == 5,
				"emeralds stay in the depot and only the loaded wheat leaves");
		} finally {
			spawned.forEach(Entity::discard);
		}
	}

	private static void gravel(ServerLevel level, BlockPos at) {
		List<Entity> spawned = new ArrayList<>();
		try {
			VillageRef ref = Checks090.fixtureForEquipment(level, at);
			BlockPos free = at.north(2);
			column(level, free.below());
			level.setBlock(free, Blocks.GRAVEL.defaultBlockState(), 3);
			BlockPos road = at.south(2);
			column(level, road.below());
			level.setBlock(road, Blocks.GRAVEL.defaultBlockState(), 3);
			level.setBlock(road.east(), Blocks.COBBLESTONE.defaultBlockState(), 3);
			level.setBlock(road.west(), Blocks.COBBLESTONE.defaultBlockState(), 3);
			var mason = villager(level, free.east(), spawned);
			check(GravelQuarry.roadSurface(level, road) && !GravelQuarry.roadSurface(level, free), "paved road gravel is protected");
			check(GravelQuarry.find(level, mason.blockPosition(), 6).equals(free), "quarry selects exposed gravel");
			JobState state = JobManager.state(mason);
			long now = level.getGameTime();
			for (int i = 0; i < 16 && level.getBlockState(free).is(Blocks.GRAVEL); i++) {
				state.target = free;
				GravelQuarry.work(context(level, ref, mason, state, now + i * 6L));
			}
			check(level.getBlockState(free).isAir() && level.getBlockState(road).is(Blocks.GRAVEL), "visible breaking removes only the free gravel");
			int gained = JobUtil.count(mason.getInventory(), stack -> stack.is(Items.GRAVEL) || stack.is(Items.FLINT));
			check(gained == 1, "the shovel drop is exactly one gravel or flint");
		} finally {
			spawned.forEach(Entity::discard);
		}
	}

	private static void workstation(ServerLevel level, BlockPos at) {
		List<Entity> spawned = new ArrayList<>();
		try {
			VillageRef ref = Checks090.fixtureForEquipment(level, at);
			BlockPos table = at.north(2);
			column(level, table.below());
			level.setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
			BlockPos chest = ref.chestPos(level);
			for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) column(level, chest.relative(direction, 2).below());
			ref.chest(level).setItem(0, new net.minecraft.world.item.ItemStack(Items.COBBLESTONE, 8));
			var smith = villager(level, table.east(), spawned);
			JobState state = JobManager.state(smith);
			Ctx ctx = context(level, ref, smith, state);
			check(Workstation.ensure(ctx, Blocks.FURNACE), "smith crafts a furnace at the workbench");
			check(JobUtil.count(ref.chest(level), stack -> stack.is(Items.FURNACE)) == 1
				&& JobUtil.count(ref.chest(level), stack -> stack.is(Items.COBBLESTONE)) == 0, "eight cobblestone become one furnace");
			state.cooldownUntil = 0;
			check(Workstation.ensure(context(level, ref, smith, state), Blocks.FURNACE), "smith places the crafted furnace");
			check(Workstation.find(level, at, Blocks.FURNACE, 8) != null
				&& JobUtil.count(ref.chest(level), stack -> stack.is(Items.FURNACE)) == 0, "the furnace leaves the chest and stands in the village");
		} finally {
			spawned.forEach(Entity::discard);
		}
	}

	private static void station(ServerLevel level, BlockPos at) {
		int rails = 0;
		boolean powered = false;
		boolean redstone = false;
		boolean roof = false;
		for (int x = 0; x <= 6; x++) {
			BlockPos floor = at.offset(x, -1, 0);
			column(level, floor);
			column(level, floor.south());
		}
		int placed = StationShed.furnish(level, new BoundingBox(at.getX(), at.getY(), at.getZ(), at.getX() + 6, at.getY() + 4, at.getZ() + 1));
		for (int x = 0; x <= 6; x++) {
			var state = level.getBlockState(at.offset(x, 0, 0));
			if (state.is(Blocks.RAIL) || state.is(Blocks.POWERED_RAIL)) rails++;
			if (state.is(Blocks.POWERED_RAIL)) powered = true;
			if (level.getBlockState(at.offset(x, 3, 0)).is(Blocks.SPRUCE_SLAB)) roof = true;
		}
		for (int x = 0; x <= 6; x++) if (level.getBlockState(at.offset(x, 0, 1)).is(Blocks.REDSTONE_BLOCK)) redstone = true;
		check(placed > 0 && rails >= 5 && powered && redstone && roof, "station shed lays covered powered track");
	}

	private static void sign(ServerLevel level, BlockPos at) {
		List<Entity> spawned = new ArrayList<>();
		try {
			column(level, at.below());
			level.setBlock(at, de.villagerplus.block.ModBlocks.DORFSCHILD.defaultBlockState(), 3);
			var resident = villager(level, at.south(2), spawned);
			resident.setVillagerData(resident.getVillagerData().withProfession(level.registryAccess(), VillagerProfession.FARMER));
			VillagerMemory memory = resident.getAttachedOrCreate(VillagerPlus.MEMORY);
			memory.name = "Elsa";
			memory.homeDoor = Optional.of(at.south());
			JobManager.state(resident).activity = "backt Brot";
			String text = String.join("\n", de.villagerplus.block.DorfschildBlock.describe(level, at));
			check(text.contains("Hier wohnt:") && text.contains("Elsa") && text.contains("Bauer")
				&& text.contains("backt Brot") && text.contains("Stimmung"), "house sign lists name, role, status and mood");
		} finally {
			spawned.forEach(Entity::discard);
		}
	}

	private static Ctx context(ServerLevel level, VillageRef ref, Villager villager, JobState state) {
		return context(level, ref, villager, state, level.getGameTime());
	}

	private static Ctx context(ServerLevel level, VillageRef ref, Villager villager, JobState state, long now) {
		return new Ctx(level, villager, state, villager.getAttachedOrCreate(VillagerPlus.MEMORY), ref, now);
	}

	private static Villager villager(ServerLevel level, BlockPos pos, List<Entity> spawned) {
		column(level, pos.below());
		var villager = EntityTypes.VILLAGER.spawn(level, pos, EntitySpawnReason.COMMAND);
		spawned.add(villager);
		villager.setNoAi(true);
		villager.setInvulnerable(true);
		villager.addTag("vp090_fixture");
		villager.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
		return villager;
	}

	private static void column(ServerLevel level, BlockPos floor) {
		level.getChunk(floor);
		level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(floor.above(), Blocks.AIR.defaultBlockState(), 3);
		level.setBlock(floor.above(2), Blocks.AIR.defaultBlockState(), 3);
	}

	private static void check(boolean ok, String label) {
		if (!ok) throw new IllegalStateException("CHECK FAILED: " + label);
		checks++;
		System.out.println("VP090 PASS " + label);
	}
}
