package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.net.RequestStockPolicy;
import de.villagerplus.net.SetStockPolicy;
import de.villagerplus.net.StockPolicyData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalItemTags;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Persistent per-item targets for items delivered into a village's communal storage. */
public final class StockPolicy {
	public static final int MAX_TARGET = 4096;
	public static final int MAX_POLICIES = 256;
	public static final int MAX_ROWS = 256;
	public static final int UNLIMITED = -1;
	private static final int MAX_ID_LENGTH = 255;
	private StockPolicy() { }

	public enum Category {
		FOOD("food"), MATERIAL("material"), TOOLS("tools"), OTHER("other");
		private final String key;
		Category(String key) { this.key = key; }
		public String key() { return key; }
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(RequestStockPolicy.TYPE, RequestStockPolicy.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SetStockPolicy.TYPE, SetStockPolicy.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(StockPolicyData.TYPE, StockPolicyData.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(RequestStockPolicy.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (!(player.level() instanceof ServerLevel level) || !validBoard(player, level, payload.board())) return;
			VillageRef ref = Villages.villageNear(level, payload.board(), 96);
			if (ref != null) ServerPlayNetworking.send(player, data(level, ref));
		});
		ServerPlayNetworking.registerGlobalReceiver(SetStockPolicy.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (!(player.level() instanceof ServerLevel level) || !validBoard(player, level, payload.board())) return;
			VillageRef ref = Villages.villageNear(level, payload.board(), 96);
			if (ref == null || payload.itemId().isEmpty() || payload.itemId().length() > MAX_ID_LENGTH
				|| payload.category().length() > 16 || payload.target() < 0 || payload.target() > MAX_TARGET) return;
			Identifier id = Identifier.tryParse(payload.itemId());
			if (id == null || !id.toString().equals(payload.itemId())) return;
			Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
			if (item == null || item == Items.AIR || !category(item).key().equals(payload.category())) return;
			Map<String, Integer> policies = policies(ref);
			if (payload.clear()) {
				policies.remove(id.toString());
			} else {
				if (!policies.containsKey(id.toString()) && policies.size() >= MAX_POLICIES) return;
				policies.put(id.toString(), payload.target());
			}
			ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES, Map.copyOf(policies));
			ref.dirty();
			ServerPlayNetworking.send(player, data(level, ref));
		});
	}

	private static boolean validBoard(ServerPlayer player, ServerLevel level, net.minecraft.core.BlockPos board) {
		return !player.isSpectator() && player.blockPosition().distSqr(board) <= 24L * 24L
			&& level.hasChunkAt(board) && level.getBlockState(board).getBlock() instanceof de.villagerplus.block.DorftafelBlock;
	}

	public static Category category(Item item) {
		ItemStack sample = new ItemStack(item);
		if (sample.isDamageableItem() || sample.is(ConventionalItemTags.TOOLS) || sample.is(ConventionalItemTags.ARMORS)
			|| sample.is(ConventionalItemTags.MELEE_WEAPON_TOOLS) || sample.is(ConventionalItemTags.RANGED_WEAPON_TOOLS)) return Category.TOOLS;
		if (de.villagerplus.job.Meals.isFood(sample) || conventionalFood(sample) || foodIngredient(item)) return Category.FOOD;
		if (item instanceof net.minecraft.world.item.BlockItem blockItem
			&& de.villagerplus.build.Supply.obtainable(blockItem.getBlock())) return Category.MATERIAL;
		if (conventionalMaterial(sample)) return Category.MATERIAL;
		String path = BuiltInRegistries.ITEM.getKey(item).getPath();
		return switch (path) {
			// Non-block ingredients used by the village's ordinary construction recipes.
			case "stick", "flint", "brick", "nether_brick", "iron_ingot", "iron_nugget", "raw_iron",
				"copper_ingot", "copper_nugget", "raw_copper", "gold_ingot", "gold_nugget", "coal", "charcoal",
				"redstone", "quartz", "string", "leather" -> Category.MATERIAL;
			default -> Category.OTHER;
		};
	}

	private static boolean conventionalFood(ItemStack stack) {
		return stack.is(ConventionalItemTags.FOODS) || stack.is(ConventionalItemTags.ANIMAL_FOODS)
			|| stack.is(ConventionalItemTags.FRUIT_FOODS) || stack.is(ConventionalItemTags.VEGETABLE_FOODS)
			|| stack.is(ConventionalItemTags.BERRY_FOODS) || stack.is(ConventionalItemTags.BREAD_FOODS)
			|| stack.is(ConventionalItemTags.COOKIE_FOODS) || stack.is(ConventionalItemTags.RAW_MEAT_FOODS)
			|| stack.is(ConventionalItemTags.COOKED_MEAT_FOODS) || stack.is(ConventionalItemTags.RAW_FISH_FOODS)
			|| stack.is(ConventionalItemTags.COOKED_FISH_FOODS) || stack.is(ConventionalItemTags.SOUP_FOODS)
			|| stack.is(ConventionalItemTags.CANDY_FOODS) || stack.is(ConventionalItemTags.PIE_FOODS)
			|| stack.is(ConventionalItemTags.CROPS) || stack.is(ConventionalItemTags.SEEDS)
			|| stack.is(ConventionalItemTags.EGGS) || stack.is(ConventionalItemTags.MUSHROOMS)
			|| stack.is(ConventionalItemTags.MILK_BUCKETS);
	}

	private static boolean conventionalMaterial(ItemStack stack) {
		return stack.is(ConventionalItemTags.STONES) || stack.is(ConventionalItemTags.COBBLESTONES)
			|| stack.is(ConventionalItemTags.GRAVELS) || stack.is(ConventionalItemTags.BRICKS)
			|| stack.is(ConventionalItemTags.INGOTS) || stack.is(ConventionalItemTags.NUGGETS)
			|| stack.is(ConventionalItemTags.RAW_MATERIALS) || stack.is(ConventionalItemTags.STRINGS)
			|| stack.is(ConventionalItemTags.LEATHERS)
			|| BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().equals("coal")
			|| BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().equals("charcoal");
	}

	private static boolean foodIngredient(Item item) {
		String path = BuiltInRegistries.ITEM.getKey(item).getPath();
		return switch (path) {
			case "wheat", "wheat_seeds", "beetroot_seeds", "melon_seeds", "pumpkin_seeds", "cocoa_beans", "sugar",
				"egg", "milk_bucket", "hay_block", "carrot", "potato", "beetroot", "rice", "rice_panicle", "onion",
				"cabbage", "tomato", "lettuce", "radish", "corn", "flour", "cheese" -> true;
			default -> path.endsWith("_seeds") || path.endsWith("_seed") || path.endsWith("_crop") || path.endsWith("_fruit")
				|| path.endsWith("_vegetable") || path.endsWith("_berry") || path.endsWith("_berries");
		};
	}

	public static int target(VillageRef ref, Item item) {
		String id = BuiltInRegistries.ITEM.getKey(item).toString();
		Integer target = ref.bellEntity().getAttachedOrElse(VillagerPlus.STOCK_POLICIES, Map.of()).get(id);
		return target != null && target >= 0 && target <= MAX_TARGET ? target : UNLIMITED;
	}

	/** Maximum count of this item the villager can currently deliver to communal storage. */
	public static int deliveryRoom(ServerLevel level, VillageRef ref, Item item) {
		int target = target(ref, item);
		if (target == UNLIMITED) return Integer.MAX_VALUE;
		return Math.max(0, target - communalCount(level, ref, item));
	}

	/** Inserts into a recognized communal store while respecting its remaining per-item allowance. */
	public static ItemStack insert(ServerLevel level, VillageRef ref, Container destination, ItemStack offered) {
		String depot = depotType(level, ref, destination);
		return insertInto(level, ref, depot, destination, offered);
	}

	/** Inserts into a known village depot without relying on container wrapper identity (double chests create fresh wrappers). */
	public static ItemStack insertInto(ServerLevel level, VillageRef ref, String depot, Container destination, ItemStack offered) {
		if (depot == null || offered.isEmpty()) return net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(null, destination, offered, null);
		if (!accepts(depot, offered.getItem())) return offered.copy();
		int allowed = Math.min(offered.getCount(), deliveryRoom(level, ref, offered.getItem()));
		if (allowed <= 0) return offered.copy();
		ItemStack moving = offered.copyWithCount(allowed);
		ItemStack insertRemainder = net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(null, destination, moving, null);
		int moved = allowed - insertRemainder.getCount();
		return offered.copyWithCount(offered.getCount() - moved);
	}

	/** Routes a village delivery to its category depot and returns every item that could not be stored. */
	public static ItemStack insertVillage(ServerLevel level, VillageRef ref, ItemStack offered) {
		if (offered.isEmpty()) return offered;
		if (category(offered.getItem()) == Category.OTHER) return offered.copy();
		String special = de.villagerplus.village.Specials.depotType(offered);
		if (special != null) {
			ItemStack rest = offered.copy();
			for (Container depot : Specials.containers(level, ref, special)) {
				if (rest.isEmpty()) break;
				rest = insertInto(level, ref, special, depot, rest);
			}
			return rest;
		}
		ItemStack rest = offered.copy();
		Container home = ref.chest(level);
		if (home != null) rest = insertInto(level, ref, de.villagerplus.build.Blueprints.STORAGE, home, rest);
		for (Container lager : Lager.containers(level, ref.data())) {
			if (rest.isEmpty()) break;
			rest = insertInto(level, ref, de.villagerplus.build.Blueprints.STORAGE, lager, rest);
		}
		return rest;
	}

	private static String depotType(ServerLevel level, VillageRef ref, Container destination) {
		for (String type : new String[] {de.villagerplus.build.Blueprints.GRANARY, de.villagerplus.build.Blueprints.TOOLSHED,
			de.villagerplus.build.Blueprints.STATION}) {
			if (Specials.containers(level, ref, type).contains(destination)) return type;
		}
		if (ref.chest(level) == destination || Lager.containers(level, ref.data()).contains(destination)) return de.villagerplus.build.Blueprints.STORAGE;
		return null;
	}

	private static boolean accepts(String depot, Item item) {
		Category category = category(item);
		return switch (depot) {
			case de.villagerplus.build.Blueprints.GRANARY -> category == Category.FOOD;
			case de.villagerplus.build.Blueprints.TOOLSHED -> category == Category.TOOLS;
			case de.villagerplus.build.Blueprints.STORAGE, de.villagerplus.build.Blueprints.STATION -> category == Category.MATERIAL;
			default -> true;
		};
	}

	/** Counts only village-owned shared stores; private shop chests and villager inventory are excluded. */
	public static int communalCount(ServerLevel level, VillageRef ref, Item item) {
		long count = 0;
		for (Container box : Balance.storage(level, ref)) {
			if (box == null) continue;
			for (int slot = 0; slot < box.getContainerSize(); slot++) {
				ItemStack stack = box.getItem(slot);
				if (!stack.isEmpty() && stack.is(item)) count += stack.getCount();
			}
		}
		return (int)Math.min(Integer.MAX_VALUE, count);
	}

	/** Computes delivery room for a worker's distinct carried item types in one communal-storage scan. */
	public static Map<Item, Integer> deliveryRooms(ServerLevel level, VillageRef ref, java.util.Collection<Item> items) {
		Map<String, Integer> saved = sanitized(ref);
		Map<Item, Integer> targets = new java.util.IdentityHashMap<>();
		Map<Item, Integer> counts = new java.util.IdentityHashMap<>();
		for (Item item : items) {
			String id = BuiltInRegistries.ITEM.getKey(item).toString();
			int target = saved.getOrDefault(id, UNLIMITED);
			if (target != UNLIMITED) {
				targets.put(item, target);
				counts.put(item, 0);
			}
		}
		if (!targets.isEmpty()) for (Container box : Balance.storage(level, ref)) {
			if (box == null) continue;
			for (int slot = 0; slot < box.getContainerSize(); slot++) {
				ItemStack stack = box.getItem(slot);
				if (stack.isEmpty() || !targets.containsKey(stack.getItem())) continue;
				Item item = stack.getItem();
				counts.merge(item, stack.getCount(), (a, b) -> (int)Math.min(Integer.MAX_VALUE, (long)a + b));
			}
		}
		Map<Item, Integer> rooms = new java.util.IdentityHashMap<>();
		for (Item item : items) {
			Integer target = targets.get(item);
			rooms.put(item, target == null ? Integer.MAX_VALUE : Math.max(0, target - counts.getOrDefault(item, 0)));
		}
		return rooms;
	}

	private static Map<String, Integer> policies(VillageRef ref) {
		return new LinkedHashMap<>(sanitized(ref));
	}

	private static Map<String, Integer> sanitized(VillageRef ref) {
		Map<String, Integer> saved = ref.bellEntity().getAttachedOrElse(VillagerPlus.STOCK_POLICIES, Map.of());
		Map<String, Integer> valid = new LinkedHashMap<>();
		for (var entry : saved.entrySet()) {
			String raw = entry.getKey();
			if (raw == null || raw.isEmpty() || raw.length() > MAX_ID_LENGTH || entry.getValue() == null
				|| entry.getValue() < 0 || entry.getValue() > MAX_TARGET) continue;
			Identifier id = Identifier.tryParse(raw);
			if (id == null || !id.toString().equals(raw) || BuiltInRegistries.ITEM.getOptional(id).isEmpty()) continue;
			valid.put(raw, entry.getValue());
			if (valid.size() >= MAX_POLICIES) break;
		}
		return valid;
	}

	public static StockPolicyData data(ServerLevel level, VillageRef ref) {
		Map<String, Integer> saved = sanitized(ref);
		Map<String, Integer> counts = new TreeMap<>();
		for (Container box : Balance.storage(level, ref)) {
			if (box == null) continue;
			for (int slot = 0; slot < box.getContainerSize(); slot++) {
				ItemStack stack = box.getItem(slot);
				if (stack.isEmpty()) continue;
				String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
				counts.merge(id, stack.getCount(), (a, b) -> (int)Math.min(Integer.MAX_VALUE, (long)a + b));
			}
		}
		List<String> ids = new ArrayList<>(saved.keySet());
		counts.keySet().stream().filter(id -> !saved.containsKey(id)).forEach(ids::add);
		ids.sort(Comparator.comparing((String id) -> !saved.containsKey(id)).thenComparing(id -> id));
		List<String> rows = new ArrayList<>();
		for (String raw : ids) {
			if (rows.size() >= MAX_ROWS) break;
			Identifier id = Identifier.tryParse(raw);
			if (id == null) continue;
			Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
			if (item == null || item == Items.AIR) continue;
			rows.add(raw + "|" + category(item).key() + "|" + counts.getOrDefault(raw, 0) + "|" + saved.getOrDefault(raw, UNLIMITED));
		}
		return new StockPolicyData(List.copyOf(rows));
	}
}
