package de.villagerplus.build;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Persistent opt-in marker and cleanup record for the phased construction flow. */
public record ConstructionSite(BlockPos origin, List<TemporaryBlock> temporary, List<Integer> phaseEnds, boolean prepared, List<String> paletteIds) {
	public static final Codec<TemporaryBlock> TEMP_CODEC = RecordCodecBuilder.create(i -> i.group(
		BlockPos.CODEC.fieldOf("pos").forGetter(TemporaryBlock::pos),
		Codec.BOOL.fieldOf("workbench").forGetter(TemporaryBlock::workbench)
	).apply(i, TemporaryBlock::new));
	public static final Codec<ConstructionSite> CODEC = RecordCodecBuilder.create(i -> i.group(
		BlockPos.CODEC.fieldOf("origin").forGetter(ConstructionSite::origin),
		TEMP_CODEC.listOf().optionalFieldOf("temporary", List.of()).forGetter(ConstructionSite::temporary),
		Codec.INT.listOf().optionalFieldOf("phase_ends", List.of()).forGetter(ConstructionSite::phaseEnds),
		Codec.BOOL.optionalFieldOf("prepared", false).forGetter(ConstructionSite::prepared),
		Codec.STRING.listOf().optionalFieldOf("palette", List.of()).forGetter(ConstructionSite::paletteIds)
	).apply(i, ConstructionSite::new));

	public record TemporaryBlock(BlockPos pos, boolean workbench) {}

	public ConstructionSite(BlockPos origin, List<TemporaryBlock> temporary) { this(origin, temporary, List.of(), false, List.of()); }
	public ConstructionSite(BlockPos origin, List<TemporaryBlock> temporary, List<Integer> phaseEnds) { this(origin, temporary, phaseEnds, false, List.of()); }
	public ConstructionSite(BlockPos origin, List<TemporaryBlock> temporary, List<Integer> phaseEnds, boolean prepared) { this(origin, temporary, phaseEnds, prepared, List.of()); }

	/** Reorder an Architect plan into semantic phases, retaining deterministic order within each phase. */
	public static List<Step> order(List<Step> input, BlockPos origin, Architect.Design design, Palette palette) {
		List<List<Step>> phases = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
		int wallTop = origin.getY() + design.wallH();
		for (Step step : input) {
			int y = step.pos().getY();
			if (y < origin.getY()) {
				phases.get(0).add(step);
			} else if (step.linkedTo() != null) {
				phases.get(2).add(step);
			} else if (!step.free() && y < wallTop && palette.frame() != null && step.item() == palette.frame().asItem()) {
				phases.get(1).add(step);
			} else if (!step.free() && y < wallTop) {
				phases.get(2).add(step);
			} else if (!step.free()) {
				phases.get(3).add(step);
			} else {
				phases.get(4).add(step);
			}
		}
		List<Step> result = new ArrayList<>(input.size());
		phases.forEach(result::addAll);
		return List.copyOf(result);
	}

	/** Preserve the historic cursor order unless this persisted plan explicitly opted into phased construction. */
	public static List<Step> forPlan(List<Step> input, BlockPos origin, Architect.Design design, Palette palette, ConstructionSite site) {
		return site != null && site.origin().equals(origin) && !site.phaseEnds().isEmpty() ? order(input, origin, design, palette) : input;
	}

	public static List<Integer> phaseEnds(List<Step> ordered, BlockPos origin, Architect.Design design, Palette palette) {
		int[] counts = new int[5];
		for (Step step : ordered) counts[phaseOf(step, origin, design, palette)]++;
		List<Integer> ends = new ArrayList<>(5);
		int sum = 0;
		for (int count : counts) { sum += count; ends.add(sum); }
		return List.copyOf(ends);
	}

	public static int phaseOf(Step step, BlockPos origin, Architect.Design design, Palette palette) {
		int y = step.pos().getY();
		int wallTop = origin.getY() + design.wallH();
		return y < origin.getY() ? 0 : step.linkedTo() != null ? 2
			: !step.free() && y < wallTop && palette.frame() != null && step.item() == palette.frame().asItem() ? 1
			: !step.free() && y < wallTop ? 2 : !step.free() ? 3 : 4;
	}

	/** Place free temporary scenery in air only, then persist every position for restart-safe cleanup. */
	public static ConstructionSite prepare(ServerLevel level, ConstructionSite site, List<Step> steps, BlockPos origin, Direction facing, Container materials) {
		if (site.prepared()) return site;
		List<Step> footprint = steps.stream().filter(s -> !s.free() && s.pos().getY() >= origin.getY() - 1 && s.pos().getY() <= origin.getY() + 8).toList();
		if (footprint.isEmpty()) return new ConstructionSite(site.origin(), site.temporary(), site.phaseEnds(), true, site.paletteIds());
		int minX = footprint.stream().mapToInt(s -> s.pos().getX()).min().orElse(origin.getX()) - 1;
		int maxX = footprint.stream().mapToInt(s -> s.pos().getX()).max().orElse(origin.getX()) + 1;
		int minZ = footprint.stream().mapToInt(s -> s.pos().getZ()).min().orElse(origin.getZ()) - 1;
		int maxZ = footprint.stream().mapToInt(s -> s.pos().getZ()).max().orElse(origin.getZ()) + 1;
		List<TemporaryBlock> placed = new ArrayList<>();
		for (int[] corner : new int[][] {{minX,minZ},{minX,maxZ},{maxX,minZ},{maxX,maxZ}}) {
			for (int dy = 0; dy < 2; dy++) {
				BlockPos p = new BlockPos(corner[0], origin.getY() + dy, corner[1]);
				place(level, materials, p, false, placed);
			}
		}
		int frontExtent = footprint.stream().mapToInt(s -> (s.pos().getX() - origin.getX()) * facing.getStepX()
			+ (s.pos().getZ() - origin.getZ()) * facing.getStepZ()).max().orElse(1);
		BlockPos bench = origin.relative(facing, frontExtent + 2);
		place(level, materials, bench, true, placed);
		return new ConstructionSite(site.origin(), List.copyOf(placed), site.phaseEnds(), true, site.paletteIds());
	}

	private static boolean place(ServerLevel level, Container materials, BlockPos pos, boolean workbench, List<TemporaryBlock> placed) {
		if (!level.hasChunkAt(pos) || !level.getBlockState(pos).isAir() || occupied(level, pos)) return false;
		BlockState state = workbench ? Blocks.CRAFTING_TABLE.defaultBlockState() : Blocks.SCAFFOLDING.defaultBlockState()
			.setValue(ScaffoldingBlock.DISTANCE, 0);
		if (!state.canSurvive(level, pos)) return false;
		Item item = workbench ? Items.CRAFTING_TABLE : Items.SCAFFOLDING;
		if (materials == null || takeOne(materials, item) == null) return false;
		if (!level.setBlock(pos, state, 3)) {
			refund(level, materials, pos, item);
			return false;
		}
		placed.add(new TemporaryBlock(pos, workbench));
		return true;
	}

	private static ItemStack takeOne(Container box, Item item) {
		for (int i = 0; i < box.getContainerSize(); i++) {
			ItemStack stack = box.getItem(i);
			if (!stack.isEmpty() && stack.is(item)) {
				ItemStack taken = box.removeItem(i, 1);
				box.setChanged();
				return taken;
			}
		}
		return null;
	}

	private static void refund(ServerLevel level, Container box, BlockPos pos, Item item) {
		ItemStack stack = new ItemStack(item);
			if (box != null) stack = HopperBlockEntity.addItem(null, box, stack, null);
		if (!stack.isEmpty()) level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level, pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, stack));
	}

	private static void refund(ServerLevel level, de.villagerplus.village.VillageRef ref, BlockPos pos, Item item) {
		ItemStack rest = new ItemStack(item);
		Container home = ref.chest(level);
		if (home != null) rest = de.villagerplus.village.StockPolicy.insertInto(level, ref,
			de.villagerplus.build.Blueprints.STORAGE, home, rest);
		for (Container box : de.villagerplus.village.Lager.containers(level, ref.data())) {
			if (rest.isEmpty()) break;
			rest = de.villagerplus.village.StockPolicy.insertInto(level, ref, de.villagerplus.build.Blueprints.STORAGE, box, rest);
		}
		if (!rest.isEmpty()) level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level, pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, rest));
	}

	public static ConstructionSite cleanup(ServerLevel level, ConstructionSite site, Container materials) {
		return cleanup(level, site, materials, null);
	}

	public static ConstructionSite cleanup(ServerLevel level, ConstructionSite site, Container materials, de.villagerplus.village.VillageRef ref) {
		List<TemporaryBlock> remaining = new ArrayList<>();
		for (TemporaryBlock temp : site.temporary()) {
			if (!level.hasChunkAt(temp.pos())) { remaining.add(temp); continue; }
			BlockState current = level.getBlockState(temp.pos());
			if (current.is(temp.workbench() ? Blocks.CRAFTING_TABLE : Blocks.SCAFFOLDING)) {
				if (level.setBlock(temp.pos(), Blocks.AIR.defaultBlockState(), 3)) {
					if (ref == null) refund(level, materials, temp.pos(), temp.workbench() ? Items.CRAFTING_TABLE : Items.SCAFFOLDING);
					else refund(level, ref, temp.pos(), temp.workbench() ? Items.CRAFTING_TABLE : Items.SCAFFOLDING);
				}
			}
		}
		return new ConstructionSite(site.origin(), List.copyOf(remaining), site.phaseEnds(), site.prepared(), site.paletteIds());
	}

	private static boolean occupied(ServerLevel level, BlockPos pos) {
		return !level.getEntitiesOfClass(LivingEntity.class, new AABB(pos), e -> true).isEmpty();
	}

	public int phaseFor(int step, int total) {
		if (total <= 0 || phaseEnds().isEmpty()) return 0;
		for (int i = 0; i < phaseEnds().size(); i++) if (step < phaseEnds().get(i)) return i;
		return 4;
	}

	public static String phaseName(int phase) {
		return switch (phase) {
			case 0 -> "Fundament";
			case 1 -> "Tragwerk";
			case 2 -> "Wände, Fenster & Türen";
			case 3 -> "Dach";
			default -> "Innenausbau & Gerüstabbau";
		};
	}
}
