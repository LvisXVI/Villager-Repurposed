package de.villagerplus.build;

import de.villagerplus.village.Sites;
import de.villagerplus.village.VillageStyle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Kopiervorlage: ein bestehendes Haus des Dorfes (um ein Bett herum). Damit sehen neue Haeuser genauso aus wie die
 * Haeuser in der Umgebung - auch bei Dorfern aus Weltgenerator-Mods (z.B. Overhauled Villages).
 *
 * <p>Abgrenzung: Bei generierten Doerfern liefert die Strukturverwaltung das Jigsaw-Teil (Piece), in dem das Bett liegt -
 * dessen Bounding Box ist genau das Haus, egal wie gross oder verschachtelt es ist. Bei selbstgebauten Haeusern wird
 * stattdessen per Flood-Fill um das Bett herum eingelesen. Bloecke mit Blockentity (Truhen, Schilder, Arbeitsbloecke)
 * werden nicht kopiert.
 */
public final class HouseTemplate {
	private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

	/** Keine praktische Obergrenze mehr: auch sehr grosse Vorlagenhaeuser werden uebernommen (Material wird bei Bedarf angefragt). */
	private static final int MAX_BLOCKS = 200_000;
	private static final int MAX_SIZE = 96;
	private static final Rotation[] ROTATIONS = {Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90};

	/** Ausdehnung nach der Drehung, relativ zum Bett (Anker). */
	public record Bounds(int minX, int maxX, int minZ, int maxZ, int maxY) { }

	private record TBlock(BlockPos rel, BlockState state) { }

	private final List<TBlock> blocks;
	private final Direction outward;
	/** Bett des Originalhauses (zum erneuten Einlesen nach einem Neustart). */
	public final BlockPos sourceBed;

	private HouseTemplate(List<TBlock> blocks, Direction outward, BlockPos sourceBed) {
		this.sourceBed = sourceBed;
		this.blocks = blocks;
		this.outward = outward;
	}

	// --- Erfassen -------------------------------------------------------------------------------

	public static boolean built(ServerLevel level, BlockPos p) {
		BlockState s = level.getBlockState(p);
		return !s.isAir() && s.getFluidState().isEmpty() && !s.canBeReplaced() && !VillageStyle.natural(s)
			&& !s.is(Blocks.DIRT_PATH) && !s.is(Blocks.FARMLAND);
	}

	/** Das Jigsaw-Teil einer generierten Struktur, in dem {@code pos} liegt (kleinstes gewinnt), sonst null. */
	private static BoundingBox pieceBox(ServerLevel level, BlockPos pos) {
		StructureStart start = level.structureManager().getStructureWithPieceAt(pos, holder -> true);
		if (start == null || !start.isValid()) {
			return null;
		}
		BoundingBox best = null;
		long bestVolume = Long.MAX_VALUE;
		for (StructurePiece piece : start.getPieces()) {
			BoundingBox b = piece.getBoundingBox();
			if (b.isInside(pos)) {
				long volume = (long) b.getXSpan() * b.getYSpan() * b.getZSpan();
				if (volume < bestVolume) {
					bestVolume = volume;
					best = b;
				}
			}
		}
		return best;
	}

	/** Liest das Haus um ein Bett herum ein. null, wenn es kein klar abgegrenztes Haus mit Tuer ist. */
	public static HouseTemplate capture(ServerLevel level, BlockPos bed) {
		return capture(level, bed, null);
	}

	/** Wie oben; {@code hint} (z.B. die Dorfglocke) bestimmt die Ausrichtung, wenn das Gebaeude keine erkennbare Tuer hat. */
	public static HouseTemplate capture(ServerLevel level, BlockPos bed, BlockPos hint) {
		if (!(level.getBlockState(bed).getBlock() instanceof BedBlock)) {
			return reject(bed, "kein Bett");
		}
		List<BlockPos> found = new ArrayList<>();
		BoundingBox box = pieceBox(level, bed);
		if (box != null) {
			if (box.getXSpan() > MAX_SIZE || box.getZSpan() > MAX_SIZE || box.getYSpan() > MAX_SIZE + 10) {
				return reject(bed, "Struktur-Teil zu gross " + box.getXSpan() + "x" + box.getYSpan() + "x" + box.getZSpan());
			}
			for (BlockPos p : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
				if (p.getY() >= bed.getY() - 1 && built(level, p)) {
					found.add(p.immutable());
				}
			}
		} else if (!flood(level, bed, found)) {
			java.util.Map<String, Integer> hist = new java.util.TreeMap<>();
			for (BlockPos q : found) {
				hist.merge(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).getPath(), 1, Integer::sum);
			}
			return reject(bed, "Flood-Fill zu gross oder unklar (" + found.size() + " Bloecke: " + hist + ")");
		}
		if (found.size() > MAX_BLOCKS) {
			return reject(bed, "zu viele Bloecke: " + found.size());
		}
		HouseTemplate t = assemble(level, bed, found, hint);
		// A Jigsaw sub-piece may contain the bed but omit the door or part of the connected shell.
		// Retry the bounded local scan before falling back to a generic box-shaped house.
		if (t == null && box != null) {
			found.clear();
			if (flood(level, bed, found)) t = assemble(level, bed, found, hint);
		}
		if (t == null) {
			return reject(bed, "keine Tuer, kein Bett oder zu wenige Bloecke (" + found.size() + ")");
		}
		LOGGER.info("Villagers: Repurposed: Haus-Vorlage bei {} uebernommen ({} Bloecke, {})", bed, t.blocks.size(), box != null ? "Struktur-Teil" : "Flood-Fill");
		return t;
	}

	private static HouseTemplate reject(BlockPos bed, String why) {
		LOGGER.info("Villagers: Repurposed: Haus bei {} ist keine Vorlage: {}", bed, why);
		return null;
	}

	/** Flood-Fill fuer Haeuser ohne Struktur-Information. false = zu gross/unklar. */
	private static boolean flood(ServerLevel level, BlockPos bed, List<BlockPos> found) {
		Set<Long> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		for (BlockPos p : BlockPos.betweenClosed(bed.offset(-2, -1, -2), bed.offset(2, 3, 2))) {
			if (built(level, p) && seen.add(p.asLong())) {
				queue.add(p.immutable());
			}
		}
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
		int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
		while (!queue.isEmpty()) {
			BlockPos p = queue.poll();
			found.add(p);
			if (found.size() > MAX_BLOCKS) {
				return false;
			}
			minX = Math.min(minX, p.getX());
			maxX = Math.max(maxX, p.getX());
			minZ = Math.min(minZ, p.getZ());
			maxZ = Math.max(maxZ, p.getZ());
			minY = Math.min(minY, p.getY());
			maxY = Math.max(maxY, p.getY());
			if (maxX - minX >= MAX_SIZE || maxZ - minZ >= MAX_SIZE || maxY - minY >= MAX_SIZE) {
				return false;
			}
			for (Direction d : Direction.values()) {
				BlockPos n = p.relative(d);
				if (n.getY() >= bed.getY() - 1 && !seen.contains(n.asLong()) && built(level, n)) {
					seen.add(n.asLong());
					queue.add(n);
				}
			}
		}
		return true;
	}

	private static HouseTemplate assemble(ServerLevel level, BlockPos bed, List<BlockPos> found, BlockPos hint) {
		// Tueren: bevorzugt eine Aussentuer (eine Seite sieht den Himmel, die andere nicht), die dem Bett am naechsten ist.
		BlockPos outerDoor = null;
		Direction outerDir = null;
		double outerDist = Double.MAX_VALUE;
		BlockPos anyDoor = null;
		double anyDist = Double.MAX_VALUE;
		boolean hasBed = false;
		List<TBlock> blocks = new ArrayList<>();
		for (BlockPos p : found) {
			BlockState s = level.getBlockState(p);
			Block b = s.getBlock();
			if (b instanceof DoorBlock d && d.type().canOpenByHand() && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
				double dist = p.distSqr(bed);
				if (dist < anyDist) {
					anyDoor = p;
					anyDist = dist;
				}
				Direction f = s.getValue(DoorBlock.FACING);
				boolean skyA = level.canSeeSky(p.relative(f));
				boolean skyB = level.canSeeSky(p.relative(f.getOpposite()));
				if (skyA != skyB && dist < outerDist) {
					outerDoor = p;
					outerDir = skyA ? f : f.getOpposite();
					outerDist = dist;
				}
			}
			if (b instanceof BedBlock) {
				hasBed |= s.getValue(BedBlock.PART) == BedPart.FOOT;
			} else if (s.hasBlockEntity() || (PoiTypes.hasPoi(s) && !s.is(BlockTags.BEDS))) {
				continue;
			}
			if (b.asItem() == Items.AIR && !(b instanceof DoorBlock)) {
				continue;
			}
			blocks.add(new TBlock(p.subtract(bed), s));
		}
		if (!hasBed || blocks.size() < 12 || anyDoor == null) {
			return null;
		}
		Direction outward = outerDir != null ? outerDir : anyDoor != null ? Sites.facingTowards(bed, anyDoor) : Sites.facingTowards(bed, hint);
		return new HouseTemplate(blocks, outward, bed.immutable());
	}

	// --- Ausrichten -----------------------------------------------------------------------------

	private Rotation rotation(Direction target, boolean mirrored) {
		Direction out = mirrored ? Mirror.LEFT_RIGHT.mirror(outward) : outward;
		return ROTATIONS[Math.floorMod(target.get2DDataValue() - out.get2DDataValue(), 4)];
	}

	/** Spiegelt (links/rechts) vor dem Drehen: dasselbe Haus wirkt dann wie ein anderes. */
	private static BlockPos mirrorPos(BlockPos rel, boolean mirrored) {
		return mirrored ? new BlockPos(rel.getX(), rel.getY(), -rel.getZ()) : rel;
	}

	public Bounds bounds(Direction target) {
		return bounds(target, false);
	}

	public Bounds bounds(Direction target, boolean mirrored) {
		Rotation rot = rotation(target, mirrored);
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE, maxY = 0;
		for (TBlock b : blocks) {
			BlockPos r = mirrorPos(b.rel(), mirrored).rotate(rot);
			minX = Math.min(minX, r.getX());
			maxX = Math.max(maxX, r.getX());
			minZ = Math.min(minZ, r.getZ());
			maxZ = Math.max(maxZ, r.getZ());
			maxY = Math.max(maxY, r.getY());
		}
		return new Bounds(minX, maxX, minZ, maxZ, maxY);
	}

	/** Rejects a copied-house site when any block on the template's lowest foundation course would hang over air. */
	public boolean supportedFoundation(ServerLevel level, BlockPos anchor, Direction target, boolean mirrored) {
		Rotation rot = rotation(target, mirrored);
		int lowest = blocks.stream().mapToInt(b -> b.rel().getY()).min().orElse(Integer.MAX_VALUE);
		for (TBlock block : blocks) {
			if (block.rel().getY() != lowest) continue;
			BlockPos relative = mirrorPos(block.rel(), mirrored).rotate(rot);
			BlockPos foundation = anchor.offset(relative);
			BlockPos below = foundation.below();
			if (!level.hasChunkAt(below) || !level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return false;
		}
		return lowest != Integer.MAX_VALUE;
	}

	/** Anzahl Betten (fuer die Statistik). */
	public int beds() {
		return (int) blocks.stream().filter(b -> b.state().getBlock() instanceof BedBlock && b.state().getValue(BedBlock.PART) == BedPart.FOOT).count();
	}

	/**
	 * Bauschritte fuer dieses Haus. {@code anchor} ist die Stelle, an der das Bett (Ursprung) landet.
	 *
	 * @param costPercent wieviel Prozent der Bloecke aus der Kiste bezahlt werden muessen
	 */
	public List<Step> steps(BlockPos anchor, Direction target, int costPercent) {
		return steps(anchor, target, costPercent, false);
	}

	public List<Step> steps(BlockPos anchor, Direction target, int costPercent, boolean mirrored) {
		return steps(anchor, target, costPercent, mirrored, b -> b);
	}

	/** {@code subst} ersetzt nicht beschaffbare Bloecke (null = Block entfaellt), siehe {@link Supply}. */
	public List<Step> steps(BlockPos anchor, Direction target, int costPercent, boolean mirrored,
		java.util.function.UnaryOperator<Block> subst) {
		return steps(anchor, target, costPercent, mirrored, subst, true);
	}

	/** {@code keepBeds = false}: Betten werden nicht mitgebaut (Lager, Schule - sonst wuerden dort neue Betten/Heimstaetten entstehen). */
	public List<Step> steps(BlockPos anchor, Direction target, int costPercent, boolean mirrored,
		java.util.function.UnaryOperator<Block> subst, boolean keepBeds) {
		Rotation rot = rotation(target, mirrored);
		List<TBlock> sorted = new ArrayList<>(blocks);
		sorted.sort(Comparator.<TBlock>comparingInt(b -> mirrorPos(b.rel(), mirrored).rotate(rot).getY())
			.thenComparingInt(b -> dependent(b.state()) ? 1 : 0)
			.thenComparingInt(b -> mirrorPos(b.rel(), mirrored).rotate(rot).getX())
			.thenComparingInt(b -> mirrorPos(b.rel(), mirrored).rotate(rot).getZ()));

		List<Step> steps = new ArrayList<>();
		int paidIndex = 0;
		for (TBlock tb : sorted) {
			BlockPos pos = anchor.offset(mirrorPos(tb.rel(), mirrored).rotate(rot));
			BlockState st = (mirrored ? tb.state().mirror(Mirror.LEFT_RIGHT) : tb.state()).rotate(rot);
			Block block = st.getBlock();
			boolean pays = costPercent >= 100 || (paidIndex * costPercent / 100) != ((paidIndex + 1) * costPercent / 100);

			if (block instanceof BedBlock) {
				if (!keepBeds) {
					continue;
				}
				Direction f = st.getValue(BedBlock.FACING);
				if (st.getValue(BedBlock.PART) == BedPart.FOOT) {
					steps.add(new Step(pos, block.asItem(), pays ? ItemTags.BEDS : null, pays ? 1 : 0, false, null,
						b -> b.defaultBlockState().setValue(BedBlock.FACING, f).setValue(BedBlock.PART, BedPart.FOOT)));
					paidIndex++;
				} else {
					steps.add(Step.linked(pos, pos.relative(f.getOpposite()),
						b -> b.defaultBlockState().setValue(BedBlock.FACING, f).setValue(BedBlock.PART, BedPart.HEAD)));
				}
			} else if (block instanceof DoorBlock) {
				if (st.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
					steps.add(new Step(pos, block.asItem(), pays ? ItemTags.WOODEN_DOORS : null, pays ? 1 : 0, false, null,
						b -> copyDoor(b, st)));
					paidIndex++;
				} else {
					steps.add(Step.linked(pos, pos.below(), b -> copyDoor(b, st)));
				}
			} else {
				Block sub = subst.apply(block);
				if (sub == null) {
					continue;
				}
				BlockState out = sub == block ? st : Supply.transfer(st, sub);
				if (pays) {
					steps.add(new Step(pos, sub.asItem(), null, 1, false, null, b -> out));
				} else {
					steps.add(Step.free(pos, out));
				}
				paidIndex++;
			}
		}
		return steps;
	}

	private static boolean dependent(BlockState s) {
		return (s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.HEAD)
			|| (s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER);
	}

	private static BlockState copyDoor(Block block, BlockState like) {
		BlockState s = block.defaultBlockState();
		if (!(block instanceof DoorBlock)) {
			return like;
		}
		return s.setValue(DoorBlock.FACING, like.getValue(DoorBlock.FACING))
			.setValue(DoorBlock.HINGE, like.getValue(DoorBlock.HINGE))
			.setValue(DoorBlock.HALF, like.getValue(DoorBlock.HALF));
	}
}
