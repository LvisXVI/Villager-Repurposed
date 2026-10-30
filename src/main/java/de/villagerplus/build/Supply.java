package de.villagerplus.build;

import de.villagerplus.village.VillageStyle;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Beschaffbarkeit: Das Dorf kann nur Material liefern, das seine Handwerker herstellen oder abbauen koennen (Holz, Stein,
 * Erde, Sand, Glas, Wolle). Fremde Vorlagen-Bloecke (z.B. bunte Terrakotta oder Backstein aus Weltgenerator-Doerfern) wuerden den
 * Bau sonst fuer immer blockieren. Sie werden durch das passendste beschaffbare Gegenstueck ersetzt (Treppe -> Treppe des Dorfstils,
 * Vollblock -> Wandmaterial, ...); Zierrat ohne Ersatz (Laternen, Teppiche ...) entfaellt.
 */
public final class Supply {
	private static final Set<String> WOODS = Set.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "cherry", "mangrove", "bamboo", "pale_oak");
	private static final Set<String> STONES = Set.of("stone", "cobblestone", "stone_brick", "mossy_cobblestone", "andesite", "diorite", "granite",
		"sandstone", "smooth_sandstone", "cut_sandstone", "red_sandstone");
	private static final Set<String> SIMPLE = Set.of("dirt", "coarse_dirt", "sand", "red_sand", "gravel", "cobblestone", "stone", "stone_bricks", "andesite",
		"diorite", "granite", "sandstone", "smooth_sandstone", "cut_sandstone", "red_sandstone", "glass", "glass_pane", "torch", "wall_torch", "mossy_cobblestone");
	private static final String[] WOOD_PARTS = {"planks", "log", "wood", "stairs", "slab", "fence", "fence_gate", "door", "trapdoor", "pressure_plate", "button"};
	private static final String[] SHAPES = {"_stairs", "_slab", "_wall"};

	private Supply() {
	}

	private static String path(Block b) {
		return BuiltInRegistries.BLOCK.getKey(b).getPath();
	}

	public static boolean obtainable(Block b) {
		// Holz aus anderen Mods: ueber die Vanilla-Tags erkannt (jede Holzart, die sich normal verarbeiten laesst).
		BlockState st = b.defaultBlockState();
		if (st.is(BlockTags.PLANKS) || st.is(BlockTags.LOGS) || st.is(BlockTags.WOODEN_STAIRS) || st.is(BlockTags.WOODEN_SLABS)
			|| st.is(BlockTags.WOODEN_FENCES) || st.is(BlockTags.FENCE_GATES) || st.is(BlockTags.WOODEN_DOORS) || st.is(BlockTags.WOODEN_TRAPDOORS)
			|| st.is(BlockTags.WOODEN_BUTTONS) || st.is(BlockTags.WOODEN_PRESSURE_PLATES) || st.is(BlockTags.WOOL)) {
			return true;
		}
		String p = path(b);
		if (SIMPLE.contains(p) || p.endsWith("_wool")) {
			return true;
		}
		String bare = p.startsWith("stripped_") ? p.substring("stripped_".length()) : p;
		for (String wood : WOODS) {
			if (bare.startsWith(wood + "_")) {
				String rest = bare.substring(wood.length() + 1);
				for (String part : WOOD_PARTS) {
					if (rest.equals(part)) {
						return true;
					}
				}
			}
		}
		for (String shape : SHAPES) {
			if (p.endsWith(shape) && STONES.contains(p.substring(0, p.length() - shape.length()))) {
				return true;
			}
		}
		return false;
	}

	/** Beschaffbarer Ersatz fuer {@code b} im Stil des Dorfes; null = der Block entfaellt. */
	public static Block substitute(Block b, VillageStyle style) {
		if (obtainable(b)) {
			return b;
		}
		String p = path(b);
		if (p.contains("leaves") || p.contains("vine") || p.contains("banner") || p.contains("carpet") || p.contains("lantern") || p.contains("pot")) {
			return null;
		}
		if (b instanceof StairBlock) {
			return style.stairs() instanceof StairBlock && obtainable(style.stairs()) ? style.stairs() : Blocks.COBBLESTONE_STAIRS;
		}
		if (b instanceof SlabBlock) {
			Block slab = slabOf(style.floor());
			return obtainable(slab) ? slab : Blocks.COBBLESTONE_SLAB;
		}
		if (b instanceof WallBlock) {
			return Blocks.COBBLESTONE_WALL;
		}
		if (b instanceof FenceBlock) {
			return style.fence() instanceof FenceBlock && obtainable(style.fence()) ? style.fence() : Blocks.OAK_FENCE;
		}
		if (b instanceof FenceGateBlock) {
			return Blocks.OAK_FENCE_GATE;
		}
		if (b instanceof IronBarsBlock || p.contains("glass")) {
			return p.contains("pane") || b instanceof IronBarsBlock ? Blocks.GLASS_PANE : Blocks.GLASS;
		}
		if (b.defaultBlockState().isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
			// Das Wandmaterial des Dorfes selbst kann exotisch sein (z. B. Terrakotta): dann gibt es schlichten Bruchstein.
			return obtainable(style.wall()) ? style.wall() : Blocks.COBBLESTONE;
		}
		return null;
	}

	private static Block slabOf(Block floor) {
		String p = path(floor);
		for (String cand : new String[] {p.replace("_planks", "") + "_slab", p + "_slab"}) {
			Block slab = BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(cand)).orElse(null);
			if (slab instanceof SlabBlock) {
				return slab;
			}
		}
		return Blocks.COBBLESTONE_SLAB;
	}

	/** Uebertraegt Ausrichtung/Haelfte/Form von {@code from} auf den Ersatzblock. */
	public static BlockState transfer(BlockState from, Block to) {
		BlockState s = to.defaultBlockState();
		for (Property<?> p : from.getProperties()) {
			if (s.hasProperty(p)) {
				s = copy(s, from, p);
			}
		}
		return s;
	}

	private static <T extends Comparable<T>> BlockState copy(BlockState s, BlockState from, Property<T> p) {
		return s.setValue(p, from.getValue(p));
	}
}
