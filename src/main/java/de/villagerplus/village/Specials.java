package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** Pflicht-Meilensteine eines Dorfes (Kornspeicher, Werkzeugkammer, Dorfkueche): Typ -> Ursprung des Gebaeudes. */
public final class Specials {
	private Specials() {
	}

	public static Map<String, BlockPos> all(VillageRef ref) {
		Map<String, BlockPos> m = ref.bellEntity().getAttached(VillagerPlus.SPECIALS);
		return m == null ? Map.of() : m;
	}

	public static boolean has(VillageRef ref, String type) {
		return all(ref).containsKey(type);
	}

	public static BlockPos get(VillageRef ref, String type) {
		return all(ref).get(type);
	}

	public static void set(VillageRef ref, String type, BlockPos origin) {
		Map<String, BlockPos> m = new HashMap<>(all(ref));
		m.put(type, origin);
		ref.bellEntity().setAttached(VillagerPlus.SPECIALS, Map.copyOf(m));
		ref.dirty();
	}

	/** Zielgebaeude fuer einen Gegenstand: Nahrung -> Kornspeicher, Werkzeug/Waffe/Ruestung -> Werkzeugkammer (sonst null). */
	public static String depotType(net.minecraft.world.item.ItemStack stack) {
		if (stack.isEmpty()) {
			return null;
		}
		return switch (StockPolicy.category(stack.getItem())) {
			case FOOD -> Blueprints_GRANARY;
			case TOOLS -> Blueprints_TOOLSHED;
			case MATERIAL -> null;
			case OTHER -> null;
		};
	}

	private static final String Blueprints_GRANARY = de.villagerplus.build.Blueprints.GRANARY;
	private static final String Blueprints_TOOLSHED = de.villagerplus.build.Blueprints.TOOLSHED;

	/** Kiste/Fass des Meilenstein-Gebaeudes mit freiem Platz fuer diesen Gegenstand (sonst null). */
	public static BlockPos depotWithRoom(net.minecraft.server.level.ServerLevel level, VillageRef ref, String type, net.minecraft.world.item.ItemStack sample) {
		BlockPos origin = get(ref, type);
		if (origin == null || !level.hasChunkAt(origin)) {
			return null;
		}
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-9, -2, -9), origin.offset(9, 8, 9))) {
			var state = level.getBlockState(pos);
			var blockEntity = level.getBlockEntity(pos);
			if (blockEntity != null && blockEntity.getAttached(VillagerPlus.OWNER) != null) continue;
			boolean chest = state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
				&& state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.RIGHT;
			if (!chest && !(state.getBlock() instanceof net.minecraft.world.level.block.BarrelBlock)) {
				continue;
			}
			var box = net.minecraft.world.level.block.entity.HopperBlockEntity.getContainerAt(level, pos);
			if (box == null) {
				continue;
			}
			for (int i = 0; i < box.getContainerSize(); i++) {
				var in = box.getItem(i);
				if (in.isEmpty() || (net.minecraft.world.item.ItemStack.isSameItemSameComponents(in, sample) && in.getCount() < in.getMaxStackSize())) {
					return pos.immutable();
				}
			}
		}
		return null;
	}

	/** Alle Kisten und Faesser eines Meilenstein-Gebaeudes (leer, wenn es nicht steht oder nicht geladen ist). */
	public static java.util.List<net.minecraft.world.Container> containers(net.minecraft.server.level.ServerLevel level, VillageRef ref, String type) {
		java.util.List<net.minecraft.world.Container> out = new java.util.ArrayList<>();
		BlockPos origin = get(ref, type);
		if (origin == null || !level.hasChunkAt(origin)) {
			return out;
		}
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-9, -2, -9), origin.offset(9, 8, 9))) {
			var state = level.getBlockState(pos);
			var blockEntity = level.getBlockEntity(pos);
			if (blockEntity != null && blockEntity.getAttached(VillagerPlus.OWNER) != null) continue;
			boolean chest = state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
				&& state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.RIGHT;
			if (chest || state.getBlock() instanceof net.minecraft.world.level.block.BarrelBlock) {
				var box = net.minecraft.world.level.block.entity.HopperBlockEntity.getContainerAt(level, pos);
				if (box != null) {
					out.add(box);
				}
			}
		}
		return out;
	}

	public static void remove(VillageRef ref, String type) {
		Map<String, BlockPos> m = new HashMap<>(all(ref));
		m.remove(type);
		ref.bellEntity().setAttached(VillagerPlus.SPECIALS, Map.copyOf(m));
		ref.dirty();
	}
}
