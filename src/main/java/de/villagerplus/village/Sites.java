package de.villagerplus.village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/** Bauplatz-Suche: ebene, freie Flaechen ohne Baeume, Wasser oder bestehende Gebaeude. */
public final class Sites {
	private Sites() {
	}

	/** Mindestabstand zu bestehenden Gebaeuden in Bloecken (Standard 3; Notloesung in engen Doerfern 2). */
	public static int margin = 3;

	/** A plot more than twelve blocks above or below the bell is a pit or a roof, not a village stand. */
	public static boolean villageLevel(BlockPos around, int y) {
		return Math.abs(y - around.getY()) <= 12;
	}

	/** Findet einen Mittelpunkt (erste freie Luftschicht ueber dem Boden) im Ring minDist..maxDist um {@code around}. */
	public static BlockPos find(ServerLevel level, BlockPos around, RandomSource r, int minDist, int maxDist, int half, int headroom,
		int attempts) {
		for (int i = 0; i < attempts; i++) {
			double angle = r.nextDouble() * Math.PI * 2;
			double dist = minDist + r.nextInt(Math.max(1, maxDist - minDist + 1));
			int x = around.getX() + (int) Math.round(Math.cos(angle) * dist);
			int z = around.getZ() + (int) Math.round(Math.sin(angle) * dist);
			if (!level.hasChunkAt(new BlockPos(x, around.getY(), z))) {
				continue;
			}
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			if (!villageLevel(around, y)) continue;
			BlockPos c = new BlockPos(x, y, z);
			if (ok(level, c, half, headroom)) {
				return c;
			}
		}
		return null;
	}

	/** Bounded full-area fallback after random placement retries fail for a large civic building. */
	public static BlockPos findSystematic(ServerLevel level, BlockPos around, RandomSource random, int minDist, int maxDist,
		int minX, int maxX, int minZ, int maxZ, int headroom, Predicate<BlockPos> fits) {
		return findDistributed(level, around, random, minDist, maxDist, minX, maxX, minZ, maxZ, headroom, false, fits);
	}

	/** Uneven-terrain counterpart to {@link #findSystematic}. */
	public static BlockPos findTolerantSystematic(ServerLevel level, BlockPos around, RandomSource random, int minDist, int maxDist,
		int minX, int maxX, int minZ, int maxZ, int headroom, Predicate<BlockPos> fits) {
		return findDistributed(level, around, random, minDist, maxDist, minX, maxX, minZ, maxZ, headroom, true, fits);
	}

	private static BlockPos findDistributed(ServerLevel level, BlockPos around, RandomSource random, int minDist, int maxDist,
		int minX, int maxX, int minZ, int maxZ, int headroom, boolean tolerant, Predicate<BlockPos> fits) {
		if (minDist < 0 || maxDist < minDist) return null;
		final double spacing = 4.0;
		double inner = Math.max(0, minDist - spacing / 2);
		double outer = maxDist + spacing / 2;
		double sampledArea = Math.PI * (outer * outer - inner * inner);
		int candidates = Math.max(1, Math.min(4096, (int) Math.ceil(sampledArea / (spacing * spacing))));
		double phase = random.nextDouble() * Math.PI * 2.0;
		Set<Long> seen = new HashSet<>(candidates);
		for (int i = 0; i < candidates; i++) {
			double fraction = (i + 0.5) / candidates;
			double radius = Math.sqrt(minDist * (double) minDist
				+ (maxDist * (double) maxDist - minDist * (double) minDist) * fraction);
			double angle = phase + i * Math.PI * (3.0 - Math.sqrt(5.0));
			int x = around.getX() + (int) Math.round(Math.cos(angle) * radius);
			int z = around.getZ() + (int) Math.round(Math.sin(angle) * radius);
			if (!seen.add(BlockPos.asLong(x, 0, z)) || !level.hasChunkAt(new BlockPos(x, around.getY(), z))) continue;
			BlockPos candidate = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
			if (!villageLevel(around, candidate.getY())) continue;
			if (tolerant) {
				int base = tolerantBase(level, candidate, minX, maxX, minZ, maxZ, headroom);
				if (base == Integer.MIN_VALUE) continue;
				candidate = new BlockPos(x, base, z);
			} else if (!okRect(level, candidate, minX, maxX, minZ, maxZ, headroom)) {
				continue;
			}
			if (fits.test(candidate)) return candidate;
		}
		return null;
	}

	/** Zufaelliger Kandidat im Ring minDist..maxDist um {@code around}: erste freie Luftschicht ueber dem Boden (null = Chunk nicht geladen). */
	public static BlockPos candidate(ServerLevel level, BlockPos around, RandomSource r, int minDist, int maxDist) {
		double angle = r.nextDouble() * Math.PI * 2;
		double dist = minDist + r.nextInt(Math.max(1, maxDist - minDist + 1));
		int x = around.getX() + (int) Math.round(Math.cos(angle) * dist);
		int z = around.getZ() + (int) Math.round(Math.sin(angle) * dist);
		if (!level.hasChunkAt(new BlockPos(x, around.getY(), z))) {
			return null;
		}
		return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
	}

	/** Quadratischer Bauplatz um c (Innen |d| <= half, Rand 1 Block). */
	public static boolean ok(ServerLevel level, BlockPos c, int half, int headroom) {
		return okRect(level, c, -half, half, -half, half, headroom);
	}

	/** Rechteckiger Bauplatz relativ zu c: innen muss alles eben und frei sein, im 1-Block-Rand darf nichts hoeher aufragen. */
	public static boolean okRect(ServerLevel level, BlockPos c, int minX, int maxX, int minZ, int maxZ, int headroom) {
		if (Market.reserved(level, c, Math.max(Math.max(Math.abs(minX), Math.abs(maxX)), Math.max(Math.abs(minZ), Math.abs(maxZ))))) return false;
		for (int dx = minX - 1; dx <= maxX + 1; dx++) {
			for (int dz = minZ - 1; dz <= maxZ + 1; dz++) {
				int x = c.getX() + dx;
				int z = c.getZ() + dz;
				if (!level.hasChunkAt(new BlockPos(x, c.getY(), z))) {
					return false;
				}
				int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				boolean inner = dx >= minX && dx <= maxX && dz >= minZ && dz <= maxZ;
				if (!inner) {
					if (h > c.getY() + 1) {
						return false;
					}
					continue;
				}
				if (h != c.getY()) {
					return false;
				}
				BlockPos ground = new BlockPos(x, c.getY() - 1, z);
				BlockState gs = level.getBlockState(ground);
				if (!gs.isFaceSturdy(level, ground, Direction.UP) || !gs.getFluidState().isEmpty()
					|| gs.is(Blocks.FARMLAND) || gs.is(Blocks.DIRT_PATH) || gs.is(BlockTags.LEAVES)) {
					return false;
				}
				for (int dy = 0; dy < headroom; dy++) {
					BlockState s = level.getBlockState(new BlockPos(x, c.getY() + dy, z));
					if (!s.isAir() && !s.canBeReplaced()) {
						return false;
					}
				}
			}
		}
		return !touchesBuilding(level, c, minX, maxX, minZ, maxZ, headroom);
	}

	/**
	 * Abstandshalter: Im Ring von 2 Bloecken um den Bauplatz (Dachueberstand, Vorbau, Schornstein, Gasse) darf kein bestehendes
	 * Gebaeude stehen - sonst wuerden Haeuser ineinander gebaut.
	 */
	public static boolean touchesBuilding(ServerLevel level, BlockPos c, int minX, int maxX, int minZ, int maxZ, int headroom) {
		int m = margin;
		BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
		for (int dx = minX - m; dx <= maxX + m; dx++) {
			for (int dz = minZ - m; dz <= maxZ + m; dz++) {
				boolean inner = dx >= minX && dx <= maxX && dz >= minZ && dz <= maxZ;
				int x = c.getX() + dx;
				int z = c.getZ() + dz;
				if (!level.hasChunkAt(q.set(x, c.getY(), z))) {
					return true;
				}
				for (int y = c.getY() - (inner ? 3 : 1); y <= c.getY() + headroom + 3; y++) {
					BlockState s = level.getBlockState(q.set(x, y, z));
					if (s.isAir() || s.canBeReplaced() || s.getFluidState().isEmpty() == false) {
						continue;
					}
					if (VillageStyle.natural(s) || s.is(BlockTags.LEAVES) || s.is(BlockTags.OVERWORLD_NATURAL_LOGS) || s.is(Blocks.SNOW)) {
						continue;
					}
					// Kleinkram (Zaeune, Laternen, Schilder, Fackeln, Toepfe, Pflanzen) zaehlt nicht als Gebaeude.
					if (!s.isCollisionShapeFullBlock(level, q) && !(s.getBlock() instanceof net.minecraft.world.level.block.StairBlock)
						&& !(s.getBlock() instanceof net.minecraft.world.level.block.SlabBlock) && !(s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock)) {
						continue;
					}
					return true;
				}
			}
		}
		return false;
	}

	/** Wie {@link #find}, aber fuer ein beliebiges Rechteck (Vorlagen-Haeuser). */
	public static BlockPos findRect(ServerLevel level, BlockPos around, RandomSource r, int minDist, int maxDist, int minX, int maxX,
		int minZ, int maxZ, int headroom, int attempts) {
		for (int i = 0; i < attempts; i++) {
			double angle = r.nextDouble() * Math.PI * 2;
			double dist = minDist + r.nextInt(Math.max(1, maxDist - minDist + 1));
			int x = around.getX() + (int) Math.round(Math.cos(angle) * dist);
			int z = around.getZ() + (int) Math.round(Math.sin(angle) * dist);
			if (!level.hasChunkAt(new BlockPos(x, around.getY(), z))) {
				continue;
			}
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			if (!villageLevel(around, y)) continue;
			BlockPos c = new BlockPos(x, y, z);
			if (okRect(level, c, minX, maxX, minZ, maxZ, headroom)) {
				return c;
			}
		}
		return null;
	}
	public static Direction facingTowards(BlockPos from, BlockPos to) {
		int dx = to.getX() - from.getX();
		int dz = to.getZ() - from.getZ();
		if (Math.abs(dx) > Math.abs(dz)) {
			return dx > 0 ? Direction.EAST : Direction.WEST;
		}
		return dz > 0 ? Direction.SOUTH : Direction.NORTH;
	}

	/**
	 * Toleranter Bauplatz: hoechstens ein Block Hoehenunterschied, damit das Fundament dem Hang folgt statt eine hohe
	 * kuenstliche Plattform zu bilden. Der Untergrund muss natuerlich sein und frei von Gebaeuden, Wegen, Feldern und Wasser.
	 */
	public static BlockPos findTolerant(ServerLevel level, BlockPos around, RandomSource r, int minDist, int maxDist, int minX, int maxX,
		int minZ, int maxZ, int headroom, int attempts) {
		for (int i = 0; i < attempts; i++) {
			BlockPos cand = candidate(level, around, r, minDist, maxDist);
			if (cand == null) {
				continue;
			}
			int base = tolerantBase(level, cand, minX, maxX, minZ, maxZ, headroom);
			if (base != Integer.MIN_VALUE && villageLevel(around, base)) {
				return new BlockPos(cand.getX(), base, cand.getZ());
			}
		}
		return null;
	}

	/** Wie {@link #findTolerant}, aber fuer eine feste Position: gibt den Anker (x, Basishoehe, z) oder null zurueck. */
	public static BlockPos tolerantAnchor(ServerLevel level, BlockPos cand, int minX, int maxX, int minZ, int maxZ, int headroom) {
		int base = tolerantBase(level, cand, minX, maxX, minZ, maxZ, headroom);
		return base == Integer.MIN_VALUE ? null : new BlockPos(cand.getX(), base, cand.getZ());
	}

	private static int tolerantBase(ServerLevel level, BlockPos c, int minX, int maxX, int minZ, int maxZ, int headroom) {
		if (Market.reserved(level,c,Math.max(Math.max(Math.abs(minX),Math.abs(maxX)),Math.max(Math.abs(minZ),Math.abs(maxZ))))) return Integer.MIN_VALUE;
		int w = maxX - minX + 1;
		int d = maxZ - minZ + 1;
		int[] heights = new int[w * d];
		int n = 0;
		for (int dx = minX; dx <= maxX; dx++) {
			for (int dz = minZ; dz <= maxZ; dz++) {
				int x = c.getX() + dx;
				int z = c.getZ() + dz;
				if (!level.hasChunkAt(new BlockPos(x, c.getY(), z))) {
					return Integer.MIN_VALUE;
				}
				heights[n++] = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			}
		}
		int[] sorted = heights.clone();
		java.util.Arrays.sort(sorted);
		// Bauhoehe = hoechster Punkt der Flaeche; nur ein Block darf aufgefuellt werden.
		int base = sorted[sorted.length - 1];
		if (base - sorted[0] > 1) {
			return Integer.MIN_VALUE;
		}
		n = 0;
		for (int dx = minX; dx <= maxX; dx++) {
			for (int dz = minZ; dz <= maxZ; dz++) {
				int h = heights[n++];
				if (Math.abs(h - base) > 1) {
					return Integer.MIN_VALUE;
				}
				int x = c.getX() + dx;
				int z = c.getZ() + dz;
				BlockPos ground = new BlockPos(x, h - 1, z);
				BlockState gs = level.getBlockState(ground);
				if (!de.villagerplus.village.VillageStyle.natural(gs) || !gs.getFluidState().isEmpty()) {
					return Integer.MIN_VALUE;
				}
				for (int y = Math.max(h, base); y < base + headroom; y++) {
					BlockState s = level.getBlockState(new BlockPos(x, y, z));
					if (!s.isAir() && !s.canBeReplaced()) {
						return Integer.MIN_VALUE;
					}
				}
			}
		}
		return touchesBuilding(level, new BlockPos(c.getX(), base, c.getZ()), minX, maxX, minZ, maxZ, headroom) ? Integer.MIN_VALUE : base;
	}
}
