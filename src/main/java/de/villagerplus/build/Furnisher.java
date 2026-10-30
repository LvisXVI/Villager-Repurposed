package de.villagerplus.build;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Einrichtung: Lager und Schule sind Kopien echter Dorfgebaeude (gleicher Stil wie die Umgebung); innen werden sie nachtraeglich
 * eingerichtet - das Lager mit Doppelkisten an den Waenden, die Schule mit Buecherregalen und Baenken. Eingerichtet wird nur
 * auf freien, ueberdachten Bodenzellen, nie direkt an der Tuer.
 */
public final class Furnisher {
	private Furnisher() {
	}

	/** Umschliessender Quader aller Bauschritte. */
	public static BoundingBox boxOf(List<Step> steps) {
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (Step s : steps) {
			BlockPos p = s.pos();
			minX = Math.min(minX, p.getX());
			minY = Math.min(minY, p.getY());
			minZ = Math.min(minZ, p.getZ());
			maxX = Math.max(maxX, p.getX());
			maxY = Math.max(maxY, p.getY());
			maxZ = Math.max(maxZ, p.getZ());
		}
		return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
	}

	private static boolean sturdy(ServerLevel level, BlockPos p, Direction face) {
		return level.getBlockState(p).isFaceSturdy(level, p, face);
	}

	/** Freie, ueberdachte Bodenzelle (Luft, Luft darueber, fester Boden), nicht an einer Tuer. */
	private static boolean free(ServerLevel level, BlockPos p) {
		if (!level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir() || !sturdy(level, p.below(), Direction.UP)
			|| level.canSeeSky(p)) {
			return false;
		}
		for (BlockPos n : BlockPos.betweenClosed(p.offset(-1, 0, -1), p.offset(1, 1, 1))) {
			if (level.getBlockState(n).getBlock() instanceof DoorBlock) {
				return false;
			}
		}
		return true;
	}

	/** Liegt ein fester Block ueber der Zelle? (Nicht ueber das Himmelslicht: Es wird direkt nach dem Bau noch nicht neu berechnet.) */
	private static boolean roofed(ServerLevel level, BlockPos p) {
		for (int dy = 2; dy <= 14; dy++) {
			var s = level.getBlockState(p.above(dy));
			if (!s.isAir() && !s.canBeReplaced()) {
				return true;
			}
		}
		return false;
	}

	/** In allen vier Himmelsrichtungen folgt innerhalb von 10 Bloecken eine feste Wand (also im Raum, nicht auf dem Vorbau). */
	private static boolean enclosed(ServerLevel level, BlockPos p) {
		for (Direction d : Direction.Plane.HORIZONTAL) {
			boolean hit = false;
			for (int k = 1; k <= 10 && !hit; k++) {
				hit = sturdy(level, p.relative(d, k), d.getOpposite());
			}
			if (!hit) {
				return false;
			}
		}
		return true;
	}

	/** Die Richtung, in der eine feste Wand direkt neben der Zelle liegt (null = keine). */
	private static Direction wallSide(ServerLevel level, BlockPos p) {
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos n = p.relative(d);
			if (sturdy(level, n, d.getOpposite()) && sturdy(level, n.above(), d.getOpposite())) {
				return d;
			}
		}
		return null;
	}

	/**
	 * Stellt Doppelkisten an die Waende (je zwei nebeneinander, mit dem Ruecken zur Wand).
	 *
	 * @return die linken Haelften der gesetzten Doppelkisten (eine Position je Doppelkiste)
	 */
	public static List<BlockPos> placeDoubleChests(ServerLevel level, BoundingBox bb, int maxDoubles) {
		List<BlockPos> placed = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ())) {
			if (placed.size() >= maxDoubles) {
				break;
			}
			BlockPos p = pos.immutable();
			if (!free(level, p)) {
				continue;
			}
			Direction wall = wallSide(level, p);
			if (wall == null) {
				continue;
			}
			Direction facing = wall.getOpposite();
			BlockPos q = p.relative(facing.getClockWise());
			if (!free(level, q) || wallSide(level, q) != wall) {
				continue;
			}
			// Ein Platz vor der Kiste muss frei bleiben, damit man sie oeffnen kann.
			if (!level.getBlockState(p.relative(facing)).isAir() || !level.getBlockState(q.relative(facing)).isAir()) {
				continue;
			}
			level.setBlock(p, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing).setValue(ChestBlock.TYPE, ChestType.LEFT), 3);
			level.setBlock(q, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing).setValue(ChestBlock.TYPE, ChestType.RIGHT), 3);
			placed.add(p);
		}
		return placed;
	}

	/** Lehrerpult: ein Lectern an einer Wand, Vorderseite zum Raum. true = gesetzt oder schon vorhanden. */
	public static boolean placeLectern(ServerLevel level, BoundingBox bb) {
		for (BlockPos pos : BlockPos.betweenClosed(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ())) {
			if (level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.LecternBlock) {
				return true;
			}
		}
		for (BlockPos pos : BlockPos.betweenClosed(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ())) {
			BlockPos p = pos.immutable();
			if (!free(level, p)) {
				continue;
			}
			Direction wall = wallSide(level, p);
			// Vor dem Pult mindestens 4 freie Bloecke, damit Lehrer und Schueler Platz haben.
			if (wall == null || !level.getBlockState(p.relative(wall.getOpposite())).isAir() || !level.getBlockState(p.relative(wall.getOpposite(), 2)).isAir()) {
				continue;
			}
			level.setBlock(p, Blocks.LECTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LecternBlock.FACING, wall.getOpposite()), 3);
			return true;
		}
		return false;
	}

	/** Buecherregale an den Waenden (zwei hoch) und Baenke aus Treppen davor. */
	public static void furnishSchool(ServerLevel level, BoundingBox bb, Block stairs) {
		placeLectern(level, bb);
		int shelves = 0;
		int benches = 0;
		Block bench = stairs instanceof StairBlock ? stairs : Blocks.OAK_STAIRS;
		for (BlockPos pos : BlockPos.betweenClosed(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ())) {
			BlockPos p = pos.immutable();
			if (!free(level, p)) {
				continue;
			}
			Direction wall = wallSide(level, p);
			if (wall != null && shelves < 12) {
				level.setBlock(p, Blocks.BOOKSHELF.defaultBlockState(), 3);
				if (level.getBlockState(p.above()).isAir() && sturdy(level, p.above().relative(wall), wall.getOpposite())) {
					level.setBlock(p.above(), Blocks.BOOKSHELF.defaultBlockState(), 3);
				}
				shelves++;
			} else if (wall == null && benches < 8 && (p.getX() + p.getZ()) % 2 == 0) {
				level.setBlock(p, bench.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH), 3);
				benches++;
			}
		}
	}

	private static Block modded(String id, Block fallback) {
		return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.Identifier.parse(id)).orElse(fallback);
	}

	/**
	 * Einrichtung der Meilenstein-Gebaeude: Kornspeicher (Doppelkisten, Fass-Reihen, Heu), Werkzeug- und Ruestungskammer
	 * (Kisten, Amboss, Schleifstein, Schmiedetisch), Dorfkueche (Ofen, Raeucherofen, Werkbank, Kessel, Komposter; mit Farmer's Delight
	 * Kochtopf und Schneidebrett).
	 */
	public static void furnishSpecial(ServerLevel level, BoundingBox bb, String type) {
		int chests = type.equals(Blueprints.KITCHEN) ? 1 : type.equals(Blueprints.TOOLSHED) ? 2 : 4;
		placeDoubleChests(level, bb, chests);
		Block[] pool = switch (type) {
			case Blueprints.GRANARY -> new Block[] {Blocks.BARREL, Blocks.BARREL, Blocks.HAY_BLOCK, Blocks.BARREL, Blocks.COMPOSTER};
			case Blueprints.STATION -> new Block[] {Blocks.BARREL, Blocks.BARREL, Blocks.HAY_BLOCK, Blocks.BARREL, Blocks.RAIL, Blocks.POWERED_RAIL};
			case Blueprints.TOOLSHED -> new Block[] {Blocks.ANVIL, Blocks.GRINDSTONE, Blocks.SMITHING_TABLE, Blocks.BARREL, Blocks.BARREL};
		default -> new Block[] {Blocks.FURNACE, Blocks.SMOKER, Blocks.BLAST_FURNACE, Blocks.CRAFTING_TABLE, Blocks.CAULDRON, modded("farmersdelight:cooking_pot", Blocks.BARREL),
				modded("farmersdelight:cutting_board", Blocks.COMPOSTER), Blocks.BARREL};
		};
		int placed = 0;
		for (BlockPos pos : BlockPos.betweenClosed(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ())) {
			if (placed >= pool.length * 2) {
				break;
			}
			BlockPos p = pos.immutable();
			if (!free(level, p) || wallSide(level, p) == null || !(level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP))) {
				continue;
			}
			if ((p.getX() * 7 + p.getZ() * 3 + placed) % 2 != 0) {
				continue;
			}
			Block b = pool[placed % pool.length];
			var state = b.defaultBlockState();
			if (state.hasProperty(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING)) {
				state = state.setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, wallSide(level, p).getOpposite());
			}
			level.setBlock(p, state, 3);
			// Claim only thermal blocks this kitchen placement actually created; never adopt a nearby/player furnace.
			if (type.equals(Blueprints.KITCHEN) && (b == Blocks.FURNACE || b == Blocks.SMOKER || b == Blocks.BLAST_FURNACE)
				&& level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace) {
				furnace.setAttached(de.villagerplus.VillagerPlus.COMMUNAL_FURNACE, 0);
				furnace.setAttached(de.villagerplus.VillagerPlus.SMITH_FURNACE, net.minecraft.world.item.ItemStack.EMPTY);
				furnace.setChanged();
			}
			placed++;
		}
		if (type.equals(Blueprints.STATION)) StationShed.furnish(level, bb);
	}

	/** Alle Kisten eines Gebaeudes (bei Doppelkisten nur die linke Haelfte), z.B. fuer ein Lager aus einer Blaupause. */
	public static List<BlockPos> collectChests(ServerLevel level, BoundingBox bb) {
		List<BlockPos> out = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ())) {
			var s = level.getBlockState(pos);
			if (s.getBlock() instanceof ChestBlock && s.getValue(ChestBlock.TYPE) != ChestType.RIGHT) {
				out.add(pos.immutable());
			}
		}
		return out;
	}
}
