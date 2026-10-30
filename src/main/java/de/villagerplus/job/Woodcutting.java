package de.villagerplus.job;

import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Holzfaellen: Der Pfeilmacher faellt echte Baeume in der Umgebung (Stamm fuer Stamm, von oben nach unten), bringt die
 * Staemme zur Dorfkiste und pflanzt einen Setzling nach, wenn er einen hat.
 */
public final class Woodcutting {
	private static final int RADIUS = 30;
	private static final int MAX_LOGS = 80;

	private Woodcutting() {
	}

	public static boolean needed(net.minecraft.server.level.ServerLevel level, VillageRef ref, VillageData d, Container box) {
		for (String w : d.wanted) {
			String id = w.split("#")[0];
			if (id.contains("planks") || id.contains("log") || id.contains("wood") || id.contains("stem") || id.contains("fence")
				|| id.contains("door") || id.contains("stairs") || id.contains("slab") || id.contains("sign")) {
				return true;
			}
		}
		// Kein festes Limit: Holz wird beschafft, solange es im Vergleich zu Essen, Stein und Metall nicht im Ueberfluss da ist.
		return de.villagerplus.village.Balance.wants(level, ref, de.villagerplus.village.Balance.Kind.WOOD);
	}

	/** true = beschaeftigt. */
	public static boolean tick(Ctx c) {
		JobState st = c.st();
		WoodcutterWork work = c.v().getAttachedOrElse(de.villagerplus.VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty());
		if (work.removalPending()) {
			return finishPendingRemoval(c, work);
		}
		work = replantNearby(c, work);
		VillageRef ref = c.village();
		if (ref == null) return false;
		if (c.now() < st.woodCheckAt) {
			return false;
		}
		Container box = ref.chest(c.level());
		if (box == null || !needed(c.level(), ref, ref.data(), box)) {
			st.woodCheckAt = c.now() + 600;
			return false;
		}
		ServerLevel level = c.level();
		BlockPos base = work.root().orElse(null);
		if (base == null) {
			st.clearTarget();
			base = findTree(c);
			if (base == null) {
				st.woodCheckAt = c.now() + 1200;
				return false;
			}
			work = work.start(base, species(level.getBlockState(base).getBlock()));
			setWork(c, work);
		}
		// A saved cursor can outlive its canopy (leaves decay naturally); preserve the stump until every connected log is handled.
		if (!level.hasChunkAt(base)) return true;
		if (!level.getBlockState(base).is(BlockTags.OVERWORLD_NATURAL_LOGS) || !rootSoil(level.getBlockState(base.below()))) {
			work = work.finishTree();
			setWork(c, work);
			st.clearTarget();
			st.woodCheckAt = c.now() + 40;
			return true;
		}
		if (hasBuiltWoodNearby(level, base)) {
			setWork(c, work.abandonTree());
			st.clearTarget();
			st.woodCheckAt = c.now() + 1200;
			return true;
		}
		if (!base.equals(st.target) || st.targetEntity != null) {
			st.clearTarget();
			st.stuckCount = 0;
			st.bestDist = 0.0;
			st.lastMoveAt = 0;
			st.target = base;
		}
		c.setActivity("fällt einen Baum");
		Anim.hold(c, Items.IRON_AXE);
		if (!JobUtil.approach(c, base, 2.8)) {
			return true;
		}
		List<BlockPos> logs = connectedLogs(level, base);
		if (logs.isEmpty()) {
			work = work.finishTree();
			setWork(c, work);
			st.clearTarget();
			st.woodCheckAt = c.now() + 40;
			return true;
		}
		// Von oben nach unten faellen: der Stamm bleibt nicht in der Luft haengen.
		BlockPos top = logs.get(0);
		if (work.log().isEmpty() || !work.log().get().equals(top)) {
			work = work.select(top);
			setWork(c, work);
		}
		BlockState state = level.getBlockState(top);
		if (Anim.breakStep(c, top, state, Items.IRON_AXE, work.stage())) {
			List<ItemStack> drops = Block.getDrops(state, level, top, null, c.v(), new ItemStack(Items.IRON_AXE));
			work = work.claimRemoval(drops, state.getBlock());
			setWork(c, work);
			return true;
		}
		int stage = Anim.breakingStage(c.v());
		if (stage != work.stage()) setWork(c, work.withStage(stage));
		return true;
	}

	/**
	 * Naechster natuerlicher Baumstamm, der auf Erde steht. Statt jeden Block im Umkreis (rund 59 000) zu pruefen, wird je
	 * Spalte nur die oberste Hoehe gelesen (Heightmap ohne Laub): Liegt dort ein Stamm, wird er bis zum Fuss verfolgt.
	 */
	private static BlockPos findTree(Ctx c) {
		ServerLevel level = c.level();
		BlockPos center = c.v().blockPosition();
		BoundingBox village = c.village() == null ? null : de.villagerplus.village.Development.bounds(level, c.village());
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockPos best = null;
		int bestD2 = Integer.MAX_VALUE;
		for (int dx = -RADIUS; dx <= RADIUS; dx++) {
			for (int dz = -RADIUS; dz <= RADIUS; dz++) {
				int d2 = dx * dx + dz * dz;
				if (d2 >= bestD2) {
					continue;
				}
				int x = center.getX() + dx;
				int z = center.getZ() + dz;
				if (!level.hasChunk(x >> 4, z >> 4)) {
					continue;
				}
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
				if (Math.abs(y - center.getY()) > 24 || !level.getBlockState(pos.set(x, y, z)).is(BlockTags.OVERWORLD_NATURAL_LOGS)) {
					continue;
				}
				int base = y;
				while (y - base < 48 && level.getBlockState(pos.set(x, base - 1, z)).is(BlockTags.LOGS)) {
					base--;
				}
				BlockPos root = new BlockPos(x, base, z);
			boolean inVillage = village != null && root.getX() >= village.minX() && root.getX() <= village.maxX()
				&& root.getY() >= village.minY() && root.getY() <= village.maxY()
				&& root.getZ() >= village.minZ() && root.getZ() <= village.maxZ();
			if (!inVillage && isHarvestableTree(level, root)) {
					best = root;
					bestD2 = d2;
				}
			}
		}
		return best;
	}

	/** Reject rooted log columns without a nearby leaf canopy; this avoids treating timber structures as harvestable trees. */
	public static boolean hasCanopy(ServerLevel level, BlockPos base) {
		for (BlockPos p : BlockPos.betweenClosed(base.offset(-4, 2, -4), base.offset(4, 12, 4))) {
			if (level.getBlockState(p).is(BlockTags.LEAVES)) return true;
		}
		return false;
	}

	public static boolean isHarvestableTree(ServerLevel level, BlockPos base) {
		return level.getBlockState(base).is(BlockTags.OVERWORLD_NATURAL_LOGS)
			&& rootSoil(level.getBlockState(base.below())) && hasCanopy(level, base) && !hasBuiltWoodNearby(level, base);
	}

	/** Logs beside a built wooden feature are decorative/structural and remain protected. */
	private static boolean hasBuiltWoodNearby(ServerLevel level, BlockPos base) {
		for (BlockPos pos : BlockPos.betweenClosed(base.offset(-2, -1, -2), base.offset(2, 12, 2))) {
			BlockState state = level.getBlockState(pos);
			if (state.is(BlockTags.PLANKS) || state.is(BlockTags.WOODEN_FENCES) || state.is(BlockTags.FENCE_GATES)
				|| state.is(BlockTags.WOODEN_DOORS) || state.is(BlockTags.WOODEN_TRAPDOORS) || state.is(BlockTags.WOODEN_STAIRS)
				|| state.is(BlockTags.WOODEN_SLABS) || state.is(BlockTags.WOODEN_BUTTONS) || state.is(BlockTags.WOODEN_PRESSURE_PLATES)) return true;
		}
		return false;
	}

	private static boolean rootSoil(BlockState state) {
		return state.is(BlockTags.DIRT)||state.is(Blocks.GRASS_BLOCK)||state.is(Blocks.MYCELIUM)||state.is(Blocks.PODZOL)||state.is(Blocks.COARSE_DIRT);
	}

	/** Alle zusammenhaengenden Staemme ab dem Fuss, hoechste zuerst. */
	private static List<BlockPos> connectedLogs(ServerLevel level, BlockPos base) {
		Set<Long> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		List<BlockPos> out = new ArrayList<>();
		queue.add(base);
		seen.add(base.asLong());
		while (!queue.isEmpty() && out.size() < MAX_LOGS) {
			BlockPos p = queue.poll();
			if (!level.getBlockState(p).is(BlockTags.OVERWORLD_NATURAL_LOGS)) {
				continue;
			}
			out.add(p);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = 0; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						BlockPos n = p.offset(dx, dy, dz);
						if (level.getBlockState(n).is(BlockTags.OVERWORLD_NATURAL_LOGS) && seen.add(n.asLong())) {
							queue.add(n);
						}
					}
				}
			}
		}
		out.sort(Comparator.comparingInt((BlockPos p) -> p.getY()).reversed());
		return out;
	}

	private static boolean matchingSapling(String species, ItemStack stack) {
		if (!stack.is(ItemTags.SAPLINGS)) return false;
		return !species.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(species + "_sapling");
	}

	private static String species(Block log) {
		String id = BuiltInRegistries.BLOCK.getKey(log).toString();
		int split = id.indexOf(':');
		String namespace = split < 0 ? "minecraft" : id.substring(0, split);
		String path = split < 0 ? id : id.substring(split + 1);
		if (path.endsWith("_log")) path = path.substring(0, path.length() - 4);
		else if (path.endsWith("_wood")) path = path.substring(0, path.length() - 5);
		else if (path.endsWith("_stem")) path = path.substring(0, path.length() - 5);
		else return "";
		return namespace + ":" + path;
	}

	private static boolean finishPendingRemoval(Ctx c, WoodcutterWork work) {
		ServerLevel level = c.level();
		BlockPos log = work.log().orElse(null);
		if (log != null && !level.hasChunkAt(log)) return true;
		if (work.root().filter(root -> hasBuiltWoodNearby(level, root)).isPresent()) {
			setWork(c, work.abandonTree());
			return true;
		}
		if (log != null && level.getBlockState(log).is(BlockTags.OVERWORLD_NATURAL_LOGS)
			&& BuiltInRegistries.BLOCK.getKey(level.getBlockState(log).getBlock()).toString().equals(work.pendingBlock())) {
			BlockState felled = level.getBlockState(log);
			if (!level.setBlock(log, Blocks.AIR.defaultBlockState(), 3)) return true;
			Anim.blockBreak(level, log, felled);
			return true;
		}
		for (ItemStack drop : work.pendingDrops()) JobUtil.give(level, c.v(), drop.copy());
		WoodcutterWork next = work.afterRemovalDelivered();
		if (log != null && work.root().filter(log::equals).isPresent()) next = next.finishTree();
		setWork(c, next);
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		c.st().cooldownUntil = c.now() + 16;
		return true;
	}

	private static WoodcutterWork replantNearby(Ctx c, WoodcutterWork work) {
		if (work.replantSites().isEmpty()) return work;
		List<WoodcutterWork.ReplantSite> remaining = new ArrayList<>(work.replantSites());
		boolean changed = false;
		for (int i = 0; i < remaining.size(); i++) {
			WoodcutterWork.ReplantSite site = remaining.get(i);
			if (site.retryAt() > c.now()) continue;
			BlockPos base = site.pos();
			if (!c.level().hasChunkAt(base)) continue;
			if (!c.level().getBlockState(base).isAir()
				|| !rootSoil(c.level().getBlockState(base.below())) || !c.level().getBlockState(base.above()).isAir()
				|| !c.level().getBlockState(base.above(2)).isAir()) {
				remaining.remove(i--);
				changed = true;
				continue;
			}
			if (c.v().blockPosition().distSqr(base) > 20) continue;
			if (plantFromInventory(c, base, site.species()) || plantFromChest(c, base, site.species())) {
				remaining.remove(i--);
				changed = true;
			} else {
				remaining.set(i, site.retryAt(c.now() + 1200));
				changed = true;
			}
		}
		if (!changed) return work;
		WoodcutterWork updated = work.updateReplantSites(remaining);
		setWork(c, updated);
		return updated;
	}

	private static boolean plantFromInventory(Ctx c, BlockPos base, String species) {
		Container inv = c.v().getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (!matchingSapling(species, stack)) continue;
			Block sapling = Block.byItem(stack.getItem());
			if (sapling == Blocks.AIR || !c.level().setBlock(base, sapling.defaultBlockState(), 3)) return false;
			stack.shrink(1);
			inv.setChanged();
			return true;
		}
		return false;
	}

	private static boolean plantFromChest(Ctx c, BlockPos base, String species) {
		if (c.village() == null) return false;
		Container box = JobUtil.box(c);
		if (box == null) return false;
		for (int i = 0; i < box.getContainerSize(); i++) {
			ItemStack stack = box.getItem(i);
			if (!matchingSapling(species, stack)) continue;
			Block sapling = Block.byItem(stack.getItem());
			if (sapling == Blocks.AIR || !c.level().setBlock(base, sapling.defaultBlockState(), 3)) return false;
			box.removeItem(i, 1);
			box.setChanged();
			return true;
		}
		return false;
	}

	private static void setWork(Ctx c, WoodcutterWork work) {
		c.v().setAttached(de.villagerplus.VillagerPlus.WOODCUTTER_WORK, work);
	}
}
