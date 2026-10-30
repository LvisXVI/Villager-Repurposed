package de.villagerplus.build;

import de.villagerplus.village.VillageStyle;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Bauplaene. {@code c} ist immer die erste freie Luftschicht ueber dem Boden, in der Mitte des Grundrisses. */
public final class Blueprints {
	public static final String HOUSE = "house";
	public static final String FARM = "farm";
	public static final String WELL = "well";
	public static final String TOWNHALL = "townhall";
	public static final String WALL = "wall";
	public static final String PEN = "pen";
	public static final String DECOR = "decor";
	public static final String STORAGE = "storage";
	public static final String SCHOOL = "school";
	/** Kornspeicher (Lebensmittel), Werkzeug- und Ruestungskammer, Dorfkueche/Baeckerei: Pflicht-Meilensteine jedes Dorfes. */
	public static final String GRANARY = "granary";
	public static final String TOOLSHED = "toolshed";
	public static final String KITCHEN = "kitchen";
	/** Bahnhof (Spaetphase): Frachthalle mit Lagerkisten fuer den Schienenverkehr. */
	public static final String STATION = "station";

	public static boolean special(String type) {
		return type.equals(GRANARY) || type.equals(TOOLSHED) || type.equals(KITCHEN) || type.equals(STATION);
	}

	private static final Item[] SEEDS = {Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS};
	private static final Block[] CROPS = {Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS};

	private Blueprints() {
	}

	public static Item seed(int variant) {
		return SEEDS[variant];
	}

	public static int seedKinds() {
		return SEEDS.length;
	}

	public static List<Step> create(String type, VillageStyle style, BlockPos c, Direction dir, int variant) {
		return switch (type) {
			case HOUSE -> house(style, c, dir);
			case FARM -> farm(c, variant);
			case WELL -> well(style, c);
			case TOWNHALL -> hall(style, c, dir);
			case PEN -> pen(style, c, dir);
			case DECOR -> decor(style, c, dir, variant);
			case STORAGE -> storage(style, c, dir);
			case SCHOOL -> school(style, c, dir);
			default -> List.of();
		};
	}

	public static int half(String type) {
		return switch (type) {
			case HOUSE -> 2;
			case FARM -> 3;
			case TOWNHALL, PEN, STORAGE, SCHOOL, GRANARY, TOOLSHED, KITCHEN, STATION -> 4;
			case DECOR -> 3;
			default -> 1;
		};
	}

	public static int headroom(String type) {
		return type.equals(HOUSE) ? 6 : type.equals(TOWNHALL) ? 9 : type.equals(PEN) ? 3 : type.equals(DECOR) ? 7 : type.equals(STORAGE) || type.equals(SCHOOL) || special(type) ? 6 : 2;
	}

	// --- Schule (7x5, Bankreihen, Regal-Wand mit Buechern, Tafel, flaches Dach) -----------------------

	public static List<Step> school(VillageStyle style, BlockPos c, Direction dir) {
		List<Step> steps = new ArrayList<>();
		Direction perp = dir.getClockWise();
		BlockPos doorLower = c.relative(dir, 2);
		BlockPos doorUpper = doorLower.above();
		for (int i = -3; i <= 3; i++) {
			for (int j = -2; j <= 2; j++) {
				steps.add(Step.block(c.relative(perp, i).relative(dir, j).below(), style.floor()));
			}
		}
		for (int dy = 0; dy < 3; dy++) {
			for (int i = -3; i <= 3; i++) {
				for (int j = -2; j <= 2; j++) {
					if (Math.abs(i) != 3 && Math.abs(j) != 2) {
						continue;
					}
					BlockPos p = c.relative(perp, i).relative(dir, j).above(dy);
					boolean window = dy == 1 && j == 0 && Math.abs(i) == 3;
					if (p.equals(doorLower) || p.equals(doorUpper)) {
						continue;
					}
					if (window) {
						steps.add(Step.free(p, Blocks.GLASS_PANE.defaultBlockState()));
					} else {
						steps.add(Step.block(p, style.wall()));
					}
				}
			}
		}
		for (int i = -3; i <= 3; i++) {
			for (int j = -2; j <= 2; j++) {
				steps.add(Step.block(c.relative(perp, i).relative(dir, j).above(3), style.roof()));
			}
		}
		Direction doorFacing = dir.getOpposite();
		steps.add(new Step(doorLower, style.door().asItem(), ItemTags.WOODEN_DOORS, 1, false, null,
			b -> b.defaultBlockState().setValue(DoorBlock.FACING, doorFacing)));
		steps.add(Step.linked(doorUpper, doorLower,
			b -> b.defaultBlockState().setValue(DoorBlock.FACING, doorFacing).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)));
		steps.add(Step.free(c.above(2), Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true)));
		return steps;
	}

	// --- Lagerschuppen (7x5, 3 Bloecke hohe Waende, flaches Dach, 6 Truhen an der Rueckwand) ----------

	/**
	 * Zweckbauten (Lager, Schule) bestehen aus Material, das das Dorf selbst beschaffen kann (Bruchstein, Bretter, Holztuer):
	 * Passt der Dorfstil dazu, wird er uebernommen, sonst gibt es die schlichte Standardvariante.
	 */
	static VillageStyle localStyle(VillageStyle style) {
		Block wall = Supply.obtainable(style.wall()) ? style.wall() : Blocks.COBBLESTONE;
		Block floor = Supply.obtainable(style.floor()) ? style.floor() : Blocks.OAK_PLANKS;
		Block roof = Supply.obtainable(style.roof()) ? style.roof() : Blocks.OAK_PLANKS;
		Block door = Supply.obtainable(style.door()) ? style.door() : Blocks.OAK_DOOR;
		Block stairs = style.stairs() != null && Supply.obtainable(style.stairs()) ? style.stairs() : Blocks.OAK_STAIRS;
		return new VillageStyle(wall, floor, roof, stairs, door, style.fence());
	}

	public static List<Step> storage(VillageStyle style, BlockPos c, Direction dir) {
		List<Step> steps = new ArrayList<>();
		Direction perp = dir.getClockWise();
		BlockPos doorLower = c.relative(dir, 2);
		BlockPos doorUpper = doorLower.above();
		for (int i = -3; i <= 3; i++) {
			for (int j = -2; j <= 2; j++) {
				steps.add(Step.block(c.relative(perp, i).relative(dir, j).below(), style.floor()));
			}
		}
		for (int dy = 0; dy < 3; dy++) {
			for (int i = -3; i <= 3; i++) {
				for (int j = -2; j <= 2; j++) {
					if (Math.abs(i) != 3 && Math.abs(j) != 2) {
						continue;
					}
					BlockPos p = c.relative(perp, i).relative(dir, j).above(dy);
					if (!p.equals(doorLower) && !p.equals(doorUpper)) {
						steps.add(Step.block(p, style.wall()));
					}
				}
			}
		}
		for (int i = -3; i <= 3; i++) {
			for (int j = -2; j <= 2; j++) {
				steps.add(Step.block(c.relative(perp, i).relative(dir, j).above(3), style.roof()));
			}
		}
		Direction doorFacing = dir.getOpposite();
		steps.add(new Step(doorLower, style.door().asItem(), ItemTags.WOODEN_DOORS, 1, false, null,
			b -> b.defaultBlockState().setValue(DoorBlock.FACING, doorFacing)));
		steps.add(Step.linked(doorUpper, doorLower,
			b -> b.defaultBlockState().setValue(DoorBlock.FACING, doorFacing).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)));
		steps.add(Step.free(c.above(2), Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true)));
		return steps;
	}

	// --- Haus (5x5, 3 Bloecke hohe Waende, Satteldach) -------------------------------------------

	public static List<Step> house(VillageStyle style, BlockPos c, Direction dir) {
		List<Step> steps = new ArrayList<>();
		BlockPos doorLower = c.relative(dir, 2);
		BlockPos doorUpper = doorLower.above();
		Direction perp = dir.getClockWise();

		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				steps.add(Step.block(c.offset(dx, -1, dz), style.floor()));
			}
		}
		for (int dy = 0; dy < 3; dy++) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != 2) {
						continue;
					}
					BlockPos p = c.offset(dx, dy, dz);
					if (!p.equals(doorLower) && !p.equals(doorUpper)) {
						steps.add(Step.block(p, style.wall()));
					}
				}
			}
		}

		// Dach: Traufe und erste Reihe als Treppen, Mitte/First als Vollbloecke (ohne Treppen-Stil: flaches Dach).
		boolean pitched = style.stairs() != null && style.stairs() instanceof StairBlock;
		for (int i = -2; i <= 2; i++) {
			BlockPos along = c.relative(perp, i);
			for (int j = -2; j <= 2; j++) {
				BlockPos base = along.relative(dir, j);
				if (!pitched || Math.abs(j) <= 1) {
					steps.add(Step.block(base.above(3), style.roof()));
				} else {
					steps.add(stair(base.above(3), style, j > 0 ? dir : dir.getOpposite()));
				}
			}
			if (pitched) {
				steps.add(stair(along.relative(dir, 1).above(4), style, dir));
				steps.add(stair(along.relative(dir, -1).above(4), style, dir.getOpposite()));
				steps.add(Step.block(along.above(4), style.roof()));
				steps.add(Step.block(along.above(5), style.roof()));
			}
		}

		Direction doorFacing = dir.getOpposite();
		steps.add(new Step(doorLower, style.door().asItem(), ItemTags.WOODEN_DOORS, 1, false, null,
			b -> b.defaultBlockState().setValue(DoorBlock.FACING, doorFacing)));
		steps.add(Step.linked(doorUpper, doorLower,
			b -> b.defaultBlockState().setValue(DoorBlock.FACING, doorFacing).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)));

		BlockPos foot = c.relative(dir.getOpposite()).relative(perp.getOpposite());
		BlockPos head = foot.relative(perp);
		steps.add(new Step(foot, null, ItemTags.BEDS, 1, false, null,
			b -> b.defaultBlockState().setValue(BedBlock.FACING, perp).setValue(BedBlock.PART, BedPart.FOOT)));
		steps.add(Step.linked(head, foot,
			b -> b.defaultBlockState().setValue(BedBlock.FACING, perp).setValue(BedBlock.PART, BedPart.HEAD)));
		steps.add(Step.free(head.relative(perp), Blocks.TORCH.defaultBlockState()));
		return steps;
	}

	private static Step stair(BlockPos pos, VillageStyle style, Direction facing) {
		return Step.block(pos, style.stairs(), b -> b.defaultBlockState().setValue(StairBlock.FACING, facing));
	}

	// --- Feld (7x7, Wasser in der Mitte) -----------------------------------------------------------

	/**
	 * Feld mit Wasserstellen. {@code variant} = Saatgut-Art + 10 * halbe Kantenlaenge (0 = Standard 3 -> 7x7). Groessere Felder
	 * (9x9, 11x11) bekommen vier Wasserstellen, damit alles bewaessert ist.
	 */
	public static List<Step> farm(BlockPos c, int variant) {
		List<Step> steps = new ArrayList<>();
		int seedKind = variant % 10;
		int half = variant / 10 == 0 ? 3 : variant / 10;
		Item seed = SEEDS[seedKind];
		Block crop = CROPS[seedKind];
		java.util.Set<BlockPos> water = new java.util.HashSet<>();
		if (half <= 3) {
			water.add(c);
		} else {
			water.add(c.offset(-2, 0, -2));
			water.add(c.offset(2, 0, -2));
			water.add(c.offset(-2, 0, 2));
			water.add(c.offset(2, 0, 2));
		}
		for (int dx = -half; dx <= half; dx++) {
			for (int dz = -half; dz <= half; dz++) {
				BlockPos p = c.offset(dx, 0, dz);
				if (!water.contains(p)) {
					steps.add(Step.free(p.below(), Blocks.FARMLAND.defaultBlockState()));
				}
			}
		}
		for (BlockPos w : water) {
			steps.add(Step.free(w.below(), Blocks.WATER.defaultBlockState()));
		}
		for (int dx = -half; dx <= half; dx++) {
			for (int dz = -half; dz <= half; dz++) {
				BlockPos p = c.offset(dx, 0, dz);
				if (!water.contains(p)) {
					steps.add(new Step(p, seed, null, 1, true, null, b -> crop.defaultBlockState()));
				}
			}
		}
		// Schmuck ausserhalb der Beete (nichts, was Bauern im Weg steht): Laternenpfosten an zwei Ecken, Vogelscheuche, Blumen.
		int e = half + 1;
		for (int sx = -1; sx <= 1; sx += 2) {
			BlockPos post = c.offset(sx * e, 0, -e);
			steps.add(Step.free(post, Blocks.OAK_FENCE.defaultBlockState()));
			steps.add(Step.free(post.above(), Blocks.OAK_FENCE.defaultBlockState()));
			steps.add(Step.free(post.above(2), Blocks.LANTERN.defaultBlockState()));
		}
		BlockPos scare = c.offset(e, 0, e);
		steps.add(Step.free(scare, Blocks.OAK_FENCE.defaultBlockState()));
		steps.add(Step.free(scare.above(), Blocks.HAY_BLOCK.defaultBlockState()));
		steps.add(Step.free(scare.above(2), Blocks.CARVED_PUMPKIN.defaultBlockState()));
		steps.add(Step.free(c.offset(-e, 0, e), Blocks.POPPY.defaultBlockState()));
		steps.add(Step.free(c.offset(-e, 0, e - 1), Blocks.CORNFLOWER.defaultBlockState()));
		return steps;
	}

	// --- Brunnen (3x3) -----------------------------------------------------------------------------

	public static List<Step> well(VillageStyle style, BlockPos c) {
		List<Step> steps = new ArrayList<>();
		steps.add(Step.free(c.below(), Blocks.WATER.defaultBlockState()));
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx != 0 || dz != 0) {
					steps.add(Step.block(c.offset(dx, 0, dz), style.wall()));
				}
			}
		}
		return steps;
	}

	// --- Rathaus (9x7, 4 Bloecke hohe Waende, grosses Satteldach) ----------------------------------

	public static List<Step> hall(VillageStyle style, BlockPos c, Direction dir) {
		List<Step> steps = new ArrayList<>();
		Direction perp = dir.getClockWise();
		BlockPos doorLower = c.relative(dir, 3);
		BlockPos doorUpper = doorLower.above();
		for (int i = -4; i <= 4; i++) {
			for (int j = -3; j <= 3; j++) {
				steps.add(Step.block(c.relative(perp, i).relative(dir, j).below(), style.floor()));
			}
		}
		for (int dy = 0; dy < 4; dy++) {
			for (int i = -4; i <= 4; i++) {
				for (int j = -3; j <= 3; j++) {
					if (Math.abs(i) != 4 && Math.abs(j) != 3) {
						continue;
					}
					BlockPos p = c.relative(perp, i).relative(dir, j).above(dy);
					if (!p.equals(doorLower) && !p.equals(doorUpper)) {
						steps.add(Step.block(p, style.wall()));
					}
				}
			}
		}
		boolean pitched = style.stairs() != null && style.stairs() instanceof StairBlock;
		int half = 3;
		for (int level = 0; level <= (pitched ? half : 0); level++) {
			int reach = half - level;
			for (int i = -4; i <= 4; i++) {
				BlockPos along = c.relative(perp, i).above(4 + level);
				for (int j = -reach; j <= reach; j++) {
					BlockPos pos = along.relative(dir, j);
					if (pitched && level < half && Math.abs(j) == reach) {
						steps.add(stair(pos, style, j > 0 ? dir : dir.getOpposite()));
					} else if (!pitched && level == 0 || pitched) {
						steps.add(Step.block(pos, style.roof()));
					}
				}
			}
		}
		Direction doorFacing = dir.getOpposite();
		steps.add(new Step(doorLower, style.door().asItem(), ItemTags.WOODEN_DOORS, 1, false, null,
			b -> b.defaultBlockState().setValue(DoorBlock.FACING, doorFacing)));
		steps.add(Step.linked(doorUpper, doorLower,
			b -> b.defaultBlockState().setValue(DoorBlock.FACING, doorFacing).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)));
		steps.add(Step.free(c.relative(perp, -3).relative(dir, -2), Blocks.LANTERN.defaultBlockState()));
		steps.add(Step.free(c.relative(perp, 3).relative(dir, -2), Blocks.LANTERN.defaultBlockState()));
		return steps;
	}

	// --- Tiergehege (9x9 Zaun mit Tor) ---------------------------------------------------------------

	public static List<Step> pen(VillageStyle style, BlockPos c, Direction dir) {
		List<Step> steps = new ArrayList<>();
		Block gate = gateFor(style.fence());
		BlockPos gatePos = c.relative(dir, 4);
		for (int i = -4; i <= 4; i++) {
			for (int j = -4; j <= 4; j++) {
				if (Math.abs(i) != 4 && Math.abs(j) != 4) {
					continue;
				}
				BlockPos p = c.relative(dir.getClockWise(), i).relative(dir, j);
				if (p.equals(gatePos) && gate instanceof net.minecraft.world.level.block.FenceGateBlock) {
					Direction facing = dir;
					steps.add(Step.block(p, gate, b -> b.defaultBlockState().setValue(net.minecraft.world.level.block.FenceGateBlock.FACING, facing)));
				} else {
					steps.add(Step.block(p, style.fence()));
				}
			}
		}
		return steps;
	}

	/** Passendes Tor zum Zaun ("oak_fence" -> "oak_fence_gate"), sonst ein normaler Zaun. */
	public static Block gateFor(Block fence) {
		net.minecraft.resources.Identifier id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(fence);
		net.minecraft.resources.Identifier gateId = net.minecraft.resources.Identifier.fromNamespaceAndPath(id.getNamespace(), id.getPath() + "_gate");
		Block gate = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(gateId).orElse(fence);
		return gate instanceof net.minecraft.world.level.block.FenceGateBlock ? gate : fence;
	}
	// --- Reichtums-Aufbauten --------------------------------------------------------------------------

	/** Stufe 0: Laternenpfaehle, 1: Marktstand, 2: Denkmal, 3: Springbrunnen, 4: Festplatz. */
	public static List<Step> decor(VillageStyle style, BlockPos c, Direction dir, int tier) {
		List<Step> steps = new ArrayList<>();
		Direction perp = dir.getClockWise();
		switch (tier % 5) {
			case 0 -> {
				for (int i = -2; i <= 2; i += 2) {
					BlockPos post = c.relative(perp, i);
					steps.add(Step.block(post, style.fence()));
					steps.add(Step.block(post.above(), style.fence()));
					steps.add(Step.free(post.above(2), Blocks.LANTERN.defaultBlockState()));
				}
			}
			case 1 -> {
				for (int i = -1; i <= 1; i += 2) {
					for (int j = 0; j <= 1; j++) {
						BlockPos post = c.relative(perp, i).relative(dir, j);
						steps.add(Step.block(post, style.fence()));
						steps.add(Step.block(post.above(), style.fence()));
						steps.add(Step.block(post.above(2), style.fence()));
					}
				}
				for (int i = -1; i <= 1; i++) {
					for (int j = 0; j <= 1; j++) {
						steps.add(Step.free(c.relative(perp, i).relative(dir, j).above(3), Blocks.CARPET.red().defaultBlockState()));
					}
				}
				steps.add(Step.free(c.relative(dir, 0), Blocks.CRAFTING_TABLE.defaultBlockState()));
				steps.add(Step.free(c.relative(dir, 1), Blocks.HAY_BLOCK.defaultBlockState()));
			}
			case 2 -> {
				for (int i = -1; i <= 1; i++) {
					for (int j = -1; j <= 1; j++) {
						steps.add(Step.block(c.relative(perp, i).relative(dir, j), style.wall()));
					}
				}
				for (int dy = 1; dy <= 4; dy++) {
					steps.add(Step.block(c.above(dy), style.wall()));
				}
				steps.add(Step.free(c.above(5), Blocks.GOLD_BLOCK.defaultBlockState()));
				steps.add(Step.free(c.relative(perp, 1).relative(dir, 1).above(), Blocks.LANTERN.defaultBlockState()));
				steps.add(Step.free(c.relative(perp, -1).relative(dir, -1).above(), Blocks.LANTERN.defaultBlockState()));
			}
			case 3 -> {
				for (int i = -2; i <= 2; i++) {
					for (int j = -2; j <= 2; j++) {
						if (Math.max(Math.abs(i), Math.abs(j)) == 2) {
							steps.add(Step.block(c.relative(perp, i).relative(dir, j), style.wall()));
						} else if (i != 0 || j != 0) {
							steps.add(Step.free(c.relative(perp, i).relative(dir, j).below(), Blocks.WATER.defaultBlockState()));
						}
					}
				}
				steps.add(Step.block(c, style.wall()));
				steps.add(Step.block(c.above(), style.wall()));
				steps.add(Step.free(c.above(2), Blocks.LANTERN.defaultBlockState()));
			}
			default -> {
				for (int i = -3; i <= 3; i++) {
					for (int j = -3; j <= 3; j++) {
						steps.add(Step.block(c.relative(perp, i).relative(dir, j).below(), style.floor()));
					}
				}
				for (int sx = -3; sx <= 3; sx += 6) {
					for (int sz = -3; sz <= 3; sz += 6) {
						BlockPos post = c.relative(perp, sx).relative(dir, sz);
						steps.add(Step.block(post, style.fence()));
						steps.add(Step.block(post.above(), style.fence()));
						steps.add(Step.free(post.above(2), Blocks.LANTERN.defaultBlockState()));
					}
				}
				steps.add(Step.free(c, Blocks.POPPY.defaultBlockState()));
				steps.add(Step.free(c.relative(perp, 2), Blocks.DANDELION.defaultBlockState()));
				steps.add(Step.free(c.relative(perp, -2), Blocks.CORNFLOWER.defaultBlockState()));
			}
		}
		return steps;
	}}
