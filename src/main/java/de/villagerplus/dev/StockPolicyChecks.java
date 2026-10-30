package de.villagerplus.dev;

import com.mojang.serialization.JsonOps;
import de.villagerplus.VillagerPlus;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobManager;
import de.villagerplus.job.JobUtil;
import de.villagerplus.village.StockPolicy;
import de.villagerplus.village.Specials;
import de.villagerplus.village.VillageRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/** Development-only checks for persistent communal stock caps and surplus delivery. */
public final class StockPolicyChecks {
	private StockPolicyChecks() { }

	public static int run(ServerLevel level, VillageRef ref, Villager villager) {
		Map<String, Integer> oldPolicies = ref.bellEntity().getAttachedOrElse(VillagerPlus.STOCK_POLICIES, Map.of());
		Map<String, BlockPos> oldSpecials = Specials.all(ref);
		Container communal = ref.chest(level);
		if (communal == null) throw new IllegalStateException("Stock policy checks need a village chest");
		List<ItemStack> oldCommunal = snapshot(communal);
		Container inventory = villager.getInventory();
		List<ItemStack> oldInventory = snapshot(inventory);
		BlockPos privatePos = ref.bell().south(8).above();
		var oldPrivateState = level.getBlockState(privatePos);
		var oldFootingState = level.getBlockState(privatePos.below());
		BlockPos granaryPos = ref.bell().offset(-80, 0, -80), toolshedPos = ref.bell().offset(-80, 0, 80);
		BlockPos privateDepotPos = granaryPos.east(2);
		var oldGranaryState = level.getBlockState(granaryPos); var oldGranaryFoot = level.getBlockState(granaryPos.below());
		var oldToolshedState = level.getBlockState(toolshedPos); var oldToolshedFoot = level.getBlockState(toolshedPos.below());
		var oldPrivateDepotState = level.getBlockState(privateDepotPos); var oldPrivateDepotFoot = level.getBlockState(privateDepotPos.below());
		var oldPosition = villager.position();
		var oldCenterChest = ref.data().chest;
		boolean privatePlaced = false;
		boolean privateTouched = false;
		boolean depotBlocksTouched = false;
		int[] passed = {0};
		try {
			ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES, Map.of());
			check(passed, StockPolicy.target(ref, Items.BREAD) == StockPolicy.UNLIMITED, "missing policy defaults to unlimited");
			var codec = com.mojang.serialization.Codec.unboundedMap(com.mojang.serialization.Codec.STRING, com.mojang.serialization.Codec.INT);
			Map<String, Integer> sample = Map.of("minecraft:bread", 17, "minecraft:iron_ingot", 0);
			var encoded = codec.encodeStart(JsonOps.INSTANCE, sample).getOrThrow();
			check(passed, codec.parse(JsonOps.INSTANCE, encoded).getOrThrow().equals(sample), "stock policy map persists by codec round trip");

			if (!oldPrivateState.isAir()) throw new IllegalStateException("Stock policy fixture private chest position must be empty");
			privateTouched = true;
			level.setBlock(privatePos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(privatePos, Blocks.CHEST.defaultBlockState(), 3);
			var privateBe = level.getBlockEntity(privatePos);
			privateBe.setAttached(VillagerPlus.OWNER, UUID.randomUUID());
			Container privateBox = HopperBlockEntity.getContainerAt(level, privatePos);
			if (privateBox == null) throw new IllegalStateException("Private stock fixture chest was not created");
			privatePlaced = true;
			privateBox.setItem(0, new ItemStack(Items.BREAD, 40));
			ref.data().chest = java.util.Optional.empty();
			check(passed, JobUtil.chest(new Ctx(level, villager, JobManager.state(villager), villager.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime())) == null,
				"village jobs never fall back to a nearby private chest");
			ref.data().chest = oldCenterChest;

			communal.clearContent();
			communal.setItem(0, new ItemStack(Items.BREAD, 3));
			check(passed, StockPolicy.communalCount(level, ref, Items.BREAD) == 3, "private chest contents are excluded from communal count");

			inventory.clearContent();
			inventory.setItem(0, new ItemStack(Items.OAK_LOG, 8));
			BlockPos communalPos = ref.data().chest.orElseThrow();
			villager.teleportTo(communalPos.getX() + 0.5, communalPos.getY(), communalPos.getZ() + 0.5);
			setPolicy(ref, "minecraft:oak_log", 0);
			Ctx ctx = new Ctx(level, villager, JobManager.state(villager), villager.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime());
			boolean didNotDeliver = JobUtil.depositSurplus(ctx, stack -> stack.is(Items.OAK_LOG), 1, 0);
			check(passed, !didNotDeliver && total(communal, Items.OAK_LOG) == 0 && total(inventory, Items.OAK_LOG) == 8,
				"zero target prevents delivery and retains carried goods");

			communal.clearContent();
			communal.setItem(0, new ItemStack(Items.OAK_LOG, 3));
			setPolicy(ref, "minecraft:oak_log", 5);
			boolean delivered = JobUtil.depositSurplus(ctx, stack -> stack.is(Items.OAK_LOG), 1, 0);
			check(passed, delivered && total(communal, Items.OAK_LOG) == 5 && total(inventory, Items.OAK_LOG) == 6,
				"finite target moves only the remaining communal capacity");
			check(passed, total(privateBox, Items.BREAD) == 40, "delivery never moves goods into or out of private stock");

			communal.clearContent(); communal.setItem(0, new ItemStack(Items.OAK_LOG, 3)); inventory.clearContent();
			JobUtil.insert(ctx, communal, new ItemStack(Items.OAK_LOG, 8));
			check(passed, total(communal, Items.OAK_LOG) == 5 && total(inventory, Items.OAK_LOG) == 6,
				"other communal insert paths also honor remaining target capacity and retain leftovers");

			ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES, Map.of(
				"minecraft:oak_log", StockPolicy.MAX_TARGET + 1,
				"bad%identifier", 4,
				"minecraft:air", 2));
			check(passed, StockPolicy.target(ref, Items.OAK_LOG) == StockPolicy.UNLIMITED, "out-of-range saved target is sanitized to unlimited");
			var rows = StockPolicy.data(level, ref).rows();
			check(passed, rows.stream().noneMatch(row -> row.startsWith("bad%identifier|") || row.startsWith("minecraft:air|")),
				"invalid identifiers and air are excluded from policy rows");

			// Dedicated stock filters: the village chest accepts materials, while milestone depots accept only their category.
			ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES, Map.of());
			communal.clearContent();
			check(passed, StockPolicy.category(Items.SUGAR_CANE) == StockPolicy.Category.FOOD
				&& StockPolicy.category(Items.DEEPSLATE) == StockPolicy.Category.MATERIAL
				&& StockPolicy.category(Items.EMERALD) == StockPolicy.Category.OTHER,
				"conventional crop/stone tags classify food and building stock while currency remains other");
			ItemStack retainedCurrency = StockPolicy.insertVillage(level, ref, new ItemStack(Items.EMERALD));
			check(passed, retainedCurrency.getCount() == 1 && total(communal, Items.EMERALD) == 0,
				"village routing never places emerald currency in the construction depot");
			if (!level.getBlockState(granaryPos).isAir() || !level.getBlockState(toolshedPos).isAir() || !level.getBlockState(privateDepotPos).isAir())
				throw new IllegalStateException("Depot filter fixture positions must be empty");
			depotBlocksTouched = true;
			level.setBlock(granaryPos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(toolshedPos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(privateDepotPos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(granaryPos, Blocks.BARREL.defaultBlockState(), 3);
			level.setBlock(toolshedPos, Blocks.BARREL.defaultBlockState(), 3);
			level.setBlock(privateDepotPos, Blocks.CHEST.defaultBlockState(), 3);
			var privateDepotBe = level.getBlockEntity(privateDepotPos);
			if (privateDepotBe == null) throw new IllegalStateException("Private depot fixture chest is missing");
			privateDepotBe.setAttached(VillagerPlus.OWNER, UUID.randomUUID());
			Container privateDepot = HopperBlockEntity.getContainerAt(level, privateDepotPos);
			if (privateDepot == null) throw new IllegalStateException("Private depot fixture container is missing");
			privateDepot.setItem(0, new ItemStack(Items.BREAD, 40));
			Specials.set(ref, de.villagerplus.build.Blueprints.GRANARY, granaryPos);
			Specials.set(ref, de.villagerplus.build.Blueprints.TOOLSHED, toolshedPos);
			Container granary = HopperBlockEntity.getContainerAt(level, granaryPos), toolshed = HopperBlockEntity.getContainerAt(level, toolshedPos);
			if (granary == null || toolshed == null) throw new IllegalStateException("Depot filter fixture barrels are missing");
			ItemStack food = new ItemStack(Items.BREAD, 4), foodRejected = StockPolicy.insertInto(level, ref, de.villagerplus.build.Blueprints.STORAGE, communal, food);
			check(passed, foodRejected.getCount() == 4 && total(communal, Items.BREAD) == 0,
				"central materials depot rejects food without consuming the offered stack");
			ItemStack logs = new ItemStack(Items.OAK_LOG, 4), logsRejected = StockPolicy.insertInto(level, ref, de.villagerplus.build.Blueprints.STORAGE, communal, logs);
			check(passed, logsRejected.isEmpty() && total(communal, Items.OAK_LOG) == 4,
				"central depot accepts construction materials");
			ItemStack foodToGranary = StockPolicy.insertInto(level, ref, de.villagerplus.build.Blueprints.GRANARY, granary, new ItemStack(Items.BREAD, 4));
			ItemStack logsToGranary = StockPolicy.insertInto(level, ref, de.villagerplus.build.Blueprints.GRANARY, granary, new ItemStack(Items.OAK_LOG, 4));
			check(passed, foodToGranary.isEmpty() && total(granary, Items.BREAD) == 4 && logsToGranary.getCount() == 4,
				"granary accepts food and refuses construction materials");
			check(passed, !Specials.containers(level, ref, de.villagerplus.build.Blueprints.GRANARY).contains(privateDepot)
				&& StockPolicy.communalCount(level, ref, Items.BREAD) == 4,
				"personal chests near a milestone building stay private and do not inflate communal stock");
			ItemStack toolToShed = StockPolicy.insertInto(level, ref, de.villagerplus.build.Blueprints.TOOLSHED, toolshed, new ItemStack(Items.IRON_PICKAXE, 1));
			ItemStack foodToShed = StockPolicy.insertInto(level, ref, de.villagerplus.build.Blueprints.TOOLSHED, toolshed, new ItemStack(Items.BREAD, 1));
			check(passed, toolToShed.isEmpty() && total(toolshed, Items.IRON_PICKAXE) == 1 && foodToShed.getCount() == 1,
				"toolshed accepts durable equipment and refuses food");
			ItemStack routedFood = StockPolicy.insertVillage(level, ref, new ItemStack(Items.BREAD, 2));
			ItemStack routedTool = StockPolicy.insertVillage(level, ref, new ItemStack(Items.IRON_PICKAXE, 1));
			check(passed, routedFood.isEmpty() && routedTool.isEmpty() && total(granary, Items.BREAD) == 6
				&& total(toolshed, Items.IRON_PICKAXE) == 2 && total(communal, Items.BREAD) == 0,
				"village routing sends food and equipment only to their registered specialist depots");
			Specials.remove(ref, de.villagerplus.build.Blueprints.GRANARY);
			ItemStack retainedFood = StockPolicy.insertVillage(level, ref, new ItemStack(Items.BREAD, 3));
			check(passed, retainedFood.getCount() == 3 && total(communal, Items.BREAD) == 0,
				"food remains with its caller when the granary is not registered");
			return passed[0];
		} finally {
			ref.data().chest = oldCenterChest;
			ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES, oldPolicies);
			ref.bellEntity().setAttached(VillagerPlus.SPECIALS, oldSpecials);
			ref.dirty();
			restore(communal, oldCommunal);
			restore(inventory, oldInventory);
			if (privatePlaced) {
				Container privateBox = HopperBlockEntity.getContainerAt(level, privatePos);
				if (privateBox != null) privateBox.clearContent();
			}
			if (privateTouched) {
				level.setBlock(privatePos, oldPrivateState, 3);
				level.setBlock(privatePos.below(), oldFootingState, 3);
			}
			if (depotBlocksTouched) {
				for (BlockPos pos : List.of(granaryPos, toolshedPos)) {
					Container depot = HopperBlockEntity.getContainerAt(level, pos);
					if (depot != null) depot.clearContent();
				}
				Container privateDepot = HopperBlockEntity.getContainerAt(level, privateDepotPos);
				if (privateDepot != null) privateDepot.clearContent();
				level.setBlock(granaryPos, oldGranaryState, 3); level.setBlock(granaryPos.below(), oldGranaryFoot, 3);
				level.setBlock(toolshedPos, oldToolshedState, 3); level.setBlock(toolshedPos.below(), oldToolshedFoot, 3);
				level.setBlock(privateDepotPos, oldPrivateDepotState, 3); level.setBlock(privateDepotPos.below(), oldPrivateDepotFoot, 3);
			}
			villager.teleportTo(oldPosition.x, oldPosition.y, oldPosition.z);
		}
	}

	private static void setPolicy(VillageRef ref, String id, int target) {
		ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES, Map.of(id, target));
		ref.dirty();
	}

	private static void check(int[] passed, boolean ok, String label) {
		if (!ok) throw new IllegalStateException("STOCK POLICY CHECK FAILED: " + label);
		passed[0]++;
		System.out.println("STOCK POLICY PASS " + label);
	}

	private static int total(Container box, net.minecraft.world.item.Item item) {
		int count = 0;
		for (int i = 0; i < box.getContainerSize(); i++) if (box.getItem(i).is(item)) count += box.getItem(i).getCount();
		return count;
	}

	private static List<ItemStack> snapshot(Container box) {
		List<ItemStack> copy = new ArrayList<>();
		for (int i = 0; i < box.getContainerSize(); i++) copy.add(box.getItem(i).copy());
		return copy;
	}

	private static void restore(Container box, List<ItemStack> contents) {
		box.clearContent();
		for (int i = 0; i < Math.min(box.getContainerSize(), contents.size()); i++) box.setItem(i, contents.get(i).copy());
		box.setChanged();
	}
}
