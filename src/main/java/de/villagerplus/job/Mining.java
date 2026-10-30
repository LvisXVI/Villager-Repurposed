package de.villagerplus.job;

import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.VillageStyle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Bergbau: Das Dorf legt abseits der Siedlung eine Mine an (Eingang mit Schild und Laterne, Treppe nach unten, dann ein
 * waagerechter Stollen). Bergleute (Maurer, Schmiede) laufen hinein und bauen die Bloecke WIRKLICH ab: Stein, Erze
 * (inklusive angrenzender Erzadern), Erde, Kies ... Die Drops landen in ihrem Inventar und werden zur Dorfkiste gebracht.
 * Bei Wasser, Lava, Sand/Kies ueber dem Stollen oder Spawnern/Kisten wird der Stollen sicherheitshalber beendet.
 */
public final class Mining {
	private static final int MAX_HORIZONTAL = 56;
	private static final int VEIN_LIMIT = 24;

	private Mining() {
	}

	/** Welche Vorraete werden gerade gebraucht? */
	public enum Need { STONE, METAL }

	/** Kein festes Limit: gebaut wird, was im Vergleich zu Essen, Holz und dem Rest knapp ist (siehe {@code Balance}). */
	public static boolean needed(ServerLevel level, VillageRef ref, VillageData d, Container box, Need need) {
		if (need == Need.METAL) {
			return de.villagerplus.village.Balance.wants(level, ref, de.villagerplus.village.Balance.Kind.METAL);
		}
		return !d.wanted.isEmpty() || de.villagerplus.village.Balance.wants(level, ref, de.villagerplus.village.Balance.Kind.STONE);
	}

	/** true = beschaeftigt. */
	public static boolean tick(Ctx c, Need need) {
		VillageRef ref = c.village();
		if (ref == null) {
			return false;
		}
		JobState st = c.st();
		if (c.now() < st.mineCheckAt) {
			return false;
		}
		Container box = ref.chest(c.level());
		if (box == null) {
			return false;
		}
		VillageData d = ref.data();
		if (!needed(c.level(), ref, d, box, need)) {
			st.mineCheckAt = c.now() + 600;
			return false;
		}
		// Migriere gespeicherte Abschlusszustaende aus aelteren Versionen ebenfalls sofort in
		// die dauerhafte Altminenliste. So bleibt der Eingang auf Karte/Tafel als "Alte Mine"
		// sichtbar, selbst wenn gerade kein neuer Schacht angelegt werden kann.
		d.mine.filter(m -> m.done).ifPresent(m -> {
			archiveMine(d, m);
			d.mine = Optional.empty();
			ref.dirty();
		});
		if (d.mine.isEmpty()) {
			Optional<VillageData.Mine> created = create(c, ref);
			if (created.isEmpty()) {
				st.mineCheckAt = c.now() + 2400;
				return false;
			}
			d.mine = created;
			ref.dirty();
		}
		VillageData.Mine m = d.mine.get();

		c.setActivity("arbeitet in der Mine");
		Anim.hold(c, Items.IRON_PICKAXE);
		st.miningUntil = c.now() + 60;

		// Der Weg in den Stollen: ueber Land zum Eingang, dann Abschnitt fuer Abschnitt die Treppe hinunter. Die normale
		// Wegfindung der Dorfbewohner fuehrt nicht zuverlaessig in einen Stollen, deshalb laufen sie ihn festgelegt ab.
		int stand = Math.max(0, m.index - 1);
		// Vor jedem Betreten: Bruecke ueber einen Hohlraum (Hoehle, Schlucht) oder Fluessigkeit im Stollen -> Mine schliessen,
		// bevor jemand hineinfaellt (gilt auch fuer Minen, die schon vor dieser Pruefung angelegt wurden).
		if (stand > 0) {
			patchFloor(c.level(), bottom(m, stand).below());
		}
		if (stand > 0 && !safeCell(c, bottom(m, stand))) {
			finishMine(c, ref, m, "Die Mine wurde geschlossen (Hohlraum unter dem Stollen).");
			exitToSurface(c, m);
			return true;
		}
		int here = nearest(m, c.v().position());
		if (here < 0 || (here == 0 && stand > 0 && c.v().position().distanceToSqr(Vec3.atBottomCenterOf(m.entrance)) > 4.0)) {
			if (!JobUtil.approach(c, m.entrance, 1.5)) {
				return true;
			}
			if (!teleport(c, m.entrance)) {
				finishMine(c, ref, m, "Die Mine wurde geschlossen (unsicherer Eingang).");
				return true;
			}
			st.cooldownUntil = c.now() + 8;
			return true;
		}
		if (here < stand) {
			if (!teleport(c, bottom(m, here + 1))) {
				finishMine(c, ref, m, "Die Mine wurde geschlossen (unsicherer Stollenabschnitt). ");
				exitToSurface(c, m);
				return true;
			}
			st.cooldownUntil = c.now() + 8;
			return true;
		}
		if (here > stand) {
			if (!teleport(c, bottom(m, here - 1))) {
				if (!exitToSurface(c, m)) {
					return true;
				}
			}
			st.cooldownUntil = c.now() + 8;
			return true;
		}		dig(c, ref, m);
		st.cooldownUntil = c.now() + 24;
		return true;
	}

	// --- Geometrie ------------------------------------------------------------------------------

	private static int descent(VillageData.Mine m) {
		return m.entrance.getY() - m.targetY;
	}

	/** Untere Zelle des Stollenabschnitts i (auf der der Dorfbewohner steht). */
	private static BlockPos bottom(VillageData.Mine m, int i) {
		Direction dir = Direction.from2DDataValue(m.dir);
		BlockPos p = m.entrance.relative(dir, i);
		return new BlockPos(p.getX(), Math.max(m.targetY, m.entrance.getY() - i), p.getZ());
	}

	private static int cells(VillageData.Mine m, int i) {
		return i < descent(m) ? 3 : 2;
	}

	// --- Anlegen --------------------------------------------------------------------------------

	private static Optional<VillageData.Mine> create(Ctx c, VillageRef ref) {
		ServerLevel level = c.level();
		for (int attempt = 0; attempt < 30; attempt++) {
			double angle = c.v().getRandom().nextDouble() * Math.PI * 2;
			double dist = 16 + c.v().getRandom().nextInt(26);
			int x = ref.bell().getX() + (int) Math.round(Math.cos(angle) * dist);
			int z = ref.bell().getZ() + (int) Math.round(Math.sin(angle) * dist);
			if (!level.hasChunkAt(new BlockPos(x, ref.bell().getY(), z))) {
				continue;
			}
			int y0 = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			BlockPos entrance = new BlockPos(x, y0, z);
			BlockState ground = level.getBlockState(entrance.below());
			if (!VillageStyle.natural(ground) || !ground.getFluidState().isEmpty()) {
				continue;
			}
			if (ref.data().extra.oldMines.stream().anyMatch(o -> o.entrance.distSqr(entrance) < 24 * 24)) {
				continue;
			}
			int targetY = Math.min(y0 - 3, Math.max(level.getMinY() + 6, y0 - 20));
			Direction dir = Direction.Plane.HORIZONTAL.getRandomDirection(c.v().getRandom());
			VillageData.Mine m = new VillageData.Mine(entrance, dir.get2DDataValue(), 0, targetY, false);
			if (!previewOk(level, m)) {
				continue;
			}
			markEntrance(level, m, dir);
			JobUtil.tellNearbyPlayers(level, c.v(), 48, Component.literal("Das Dorf legt eine Mine an."));
			return Optional.of(m);
		}
		return Optional.empty();
	}

	/** Die ersten Abschnitte muessen im natuerlichen, trockenen Gestein liegen (nicht in Gebaeuden oder Wasser). */
	private static boolean previewOk(ServerLevel level, VillageData.Mine m) {
		int len = descent(m) + 8;
		for (int i = 0; i < len; i++) {
			BlockPos b = bottom(m, i);
			if (!level.hasChunkAt(b)) {
				return false;
			}
			for (int k = 0; k < cells(m, i); k++) {
				BlockPos p = b.above(k);
				BlockState s = level.getBlockState(p);
				if (!s.getFluidState().isEmpty() || s.hasBlockEntity() || s.getDestroySpeed(level, p) < 0) {
					return false;
				}
				if (!s.isAir() && !VillageStyle.natural(s) && !s.canBeReplaced() && !isOre(s)
					&& !s.is(net.minecraft.tags.BlockTags.OVERWORLD_NATURAL_LOGS)) {
					return false;
				}
				for (Direction d : Direction.values()) {
					if (!level.getBlockState(p.relative(d)).getFluidState().isEmpty()) {
						return false;
					}
				}
			}
		}
		return true;
	}

	/** Zaunpfosten mit Laterne am Eingang (kein Schild mehr: die Landschaft soll nicht voller Schilder stehen). */
	/** Mineneingang: Stuetzbalken-Portal aus Fichtenstaemmen (zwei Pfosten, Sturz) mit Laternen - wie ein echter Stolleneingang. */
	private static void markEntrance(ServerLevel level, VillageData.Mine m, Direction dir) {
		Direction side = dir.getClockWise();
		BlockPos e = m.entrance;
		for (int s = -1; s <= 1; s += 2) {
			BlockPos post = e.relative(side, s);
			for (int y = 0; y < 3; y++) {
				BlockPos p = post.above(y);
				BlockState cur = level.getBlockState(p);
				if (cur.isAir() || cur.canBeReplaced()) {
					level.setBlock(p, Blocks.SPRUCE_LOG.defaultBlockState(), 3);
				}
			}
			BlockPos lamp = post.relative(dir.getOpposite()).above(2);
			if (level.getBlockState(lamp).isAir() && level.getBlockState(lamp.below()).isAir()) {
				level.setBlock(lamp, Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, false), 3);
				if (!level.getBlockState(lamp.below()).isFaceSturdy(level, lamp.below(), Direction.UP)) {
					level.setBlock(lamp, Blocks.AIR.defaultBlockState(), 3);
				}
			}
		}
		for (int s = -1; s <= 1; s++) {
			BlockPos p = e.relative(side, s).above(3);
			BlockState cur = level.getBlockState(p);
			if (cur.isAir() || cur.canBeReplaced()) {
				level.setBlock(p, Blocks.SPRUCE_LOG.defaultBlockState().setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, side.getAxis()), 3);
			}
		}
	}

	/** Verschlossene Mine: Bretter und Zaun im Portal, Wandschild "[VERSCHLOSSEN - ERSCHOEPFT]" davor (Karte/Tafel fuehren sie als alte Mine). */
	public static void barricade(ServerLevel level, VillageData.Mine m, boolean exhausted) {
		Direction dir = Direction.from2DDataValue(m.dir);
		BlockPos e = m.entrance;
		if (!canSeal(level, m)) {
			return;
		}
		markEntrance(level, m, dir); // Portal sicherstellen (Pfosten, Sturz, Laternen)
		for (int y = 0; y < 3; y++) {
			BlockPos p = e.above(y);
			BlockState cur = level.getBlockState(p);
			if (cur.isAir() || cur.canBeReplaced() || cur.is(Blocks.TORCH) || cur.is(Blocks.WALL_TORCH)) {
				level.setBlock(p, y < 2 ? Blocks.SPRUCE_PLANKS.defaultBlockState() : Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
			}
		}
		Direction out = dir.getOpposite();
		BlockPos signPos = e.above(1).relative(out);
		BlockState cur = level.getBlockState(signPos);
		if (cur.isAir() || cur.canBeReplaced()) {
			level.setBlock(signPos, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(net.minecraft.world.level.block.WallSignBlock.FACING, out), 3);
			if (level.getBlockEntity(signPos) instanceof SignBlockEntity sign) {
				SignText text = sign.getFrontText()
					.setMessage(0, Component.literal("[VERSCHLOSSEN"))
					.setMessage(1, Component.literal(exhausted ? "- ERSCHÖPFT]" : "- GEFAHR]"))
					.setMessage(3, Component.literal("Alte Mine"))
					.setColor(net.minecraft.world.item.DyeColor.RED)
					.setHasGlowingText(true);
				sign.setText(text, true);
				sign.setWaxed(true);
				sign.setChanged();
			}
		}
		level.playSound(null, e, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0F, 0.8F);
	}

	/**
	 * Kompatibilitaetspunkt fuer aeltere Aufrufer: ein beendeter Eingang bleibt erhalten und
	 * wird gesichert, statt Portalbloecke oder Schilder zu entfernen.
	 */
	public static void clearEntrance(ServerLevel level, VillageData.Mine m) {
		if (m.done) {
			barricade(level, m, true);
		}
	}

	/**
	 * Raeumt Altlasten frueherer Versionen auf: Stehende Schilder "Dorfmine" und "Dorftafel" samt Laternenpfosten rund um das Dorf
	 * (die Dorftafel ist inzwischen ein eigener Block, Minen markieren ihren Eingang nur noch mit Pfosten und Laterne).
	 */
	public static int sweepLegacyMarkers(ServerLevel level, BlockPos bell) {
		int removed = 0;
		for (int x = bell.getX() - 90; x <= bell.getX() + 90; x++) {
			for (int z = bell.getZ() - 90; z <= bell.getZ() + 90; z++) {
				if (!level.hasChunkAt(new BlockPos(x, bell.getY(), z))) {
					continue;
				}
				int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				BlockPos p = null;
				for (int yy = top + 2; yy >= top - 2; yy--) {
					if (level.getBlockState(new BlockPos(x, yy, z)).is(Blocks.OAK_SIGN)) {
						p = new BlockPos(x, yy, z);
						break;
					}
				}
				if (p == null) {
					continue;
				}
				if (!(level.getBlockEntity(p) instanceof SignBlockEntity sign)) {
					continue;
				}
				String text = "";
				for (int i = 0; i < 4; i++) {
					text += sign.getFrontText().getMessage(i, false).getString() + " ";
				}
				if (!text.contains("Dorfmine") && !text.contains("Dorftafel")) {
					continue;
				}
				level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
				removed++;
				for (BlockPos q : BlockPos.betweenClosed(p.offset(-2, -1, -2), p.offset(2, 1, 2))) {
					if (level.getBlockState(q).is(Blocks.OAK_FENCE) && level.getBlockState(q.above()).is(Blocks.LANTERN) && level.getBlockState(q.below()).isFaceSturdy(level, q.below(), Direction.UP)) {
						BlockPos fq = q.immutable();
						level.setBlock(fq.above(), Blocks.AIR.defaultBlockState(), 3);
						level.setBlock(fq, Blocks.AIR.defaultBlockState(), 3);
						break;
					}
				}
			}
		}
		return removed;
	}

	// --- Abbauen --------------------------------------------------------------------------------

	private static boolean isOre(BlockState s) {
		return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath().endsWith("_ore");
	}

	private static boolean hazard(ServerLevel level, BlockPos p) {
		BlockState s = level.getBlockState(p);
		if (s.hasBlockEntity() || s.getDestroySpeed(level, p) < 0) {
			return true;
		}
		for (Direction d : Direction.values()) {
			if (!level.getBlockState(p.relative(d)).getFluidState().isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private static void dig(Ctx c, VillageRef ref, VillageData.Mine m) {
		ServerLevel level = c.level();
		BlockPos base = bottom(m, m.index);
		int cells = cells(m, m.index);
		// Sicherheit zuerst: Hohlraum/Schlucht unter dem Stollen, Fluessigkeiten, Spawner/Kisten, nachrutschende Bloecke -> beenden.
		BlockPos floor = base.below();
		patchFloor(level, floor);
		if (!level.getBlockState(floor).isFaceSturdy(level, floor, net.minecraft.core.Direction.UP) && m.index > 0) {
			finishMine(c, ref, m, "Die Mine wurde geschlossen (Hohlraum unter dem Stollen).");
			return;
		}
		for (int k = 0; k < cells; k++) {
			if (hazard(level, base.above(k))) {
				finishMine(c, ref, m, "Die Mine wurde wegen einer Gefahr (Wasser, Lava oder Spawner) geschlossen.");
				return;
			}
		}
		if (level.getBlockState(base.above(cells)).getBlock() instanceof FallingBlock) {
			finishMine(c, ref, m, "Die Mine wurde geschlossen (herabfallende Blöcke).");
			return;
		}
		List<BlockPos> dug = new ArrayList<>();
		for (int k = 0; k < cells; k++) {
			BlockPos p = base.above(k);
			if (breakBlock(c, p)) {
				dug.add(p);
			}
		}
		JobUtil.swing(c.v());
		shape(c, m, base, cells, dug);
		// Erzadern, die den Stollen beruehren, komplett mitnehmen.
		for (BlockPos p : dug) {
			for (Direction d : Direction.values()) {
				BlockPos n = p.relative(d);
				if (isOre(level.getBlockState(n))) {
					mineVein(c, n);
				}
			}
		}
		c.mem().jobsDone++;
		m.index++;
		if (m.index >= descent(m) + MAX_HORIZONTAL) {
			finishMine(c, ref, m, "Die Mine ist ausgebeutet.");
		}
		ref.dirty();
	}

	/**
	 * Macht aus dem geraden Gang einen richtigen Stollen: unregelmaessige Breite und Hoehe, Seitenstollen, Holzstuetzen mit
	 * Deckenbalken und Fackeln. Nur die mittlere Zelle ist der Laufweg - alles andere wird nur gegraben, wenn es sicher ist
	 * (kein Hohlraum darunter, keine Fluessigkeit, kein Spawner, nichts Nachrutschendes darueber).
	 */
	private static void shape(Ctx c, VillageData.Mine m, BlockPos base, int cells, List<BlockPos> dug) {
		ServerLevel level = c.level();
		net.minecraft.util.RandomSource r = c.v().getRandom();
		Direction dir = Direction.from2DDataValue(m.dir);
		Direction[] sides = {dir.getCounterClockWise(), dir.getClockWise()};
		boolean tunnel = m.index >= descent(m);

		// Breite: je Seite meist eine weitere Zelle, manchmal auch zwei Hoehe unterschiedlich.
		for (Direction side : sides) {
			if (r.nextInt(100) < 70) {
				int h = Math.max(1, cells - (r.nextInt(3) == 0 ? 1 : 0));
				carveColumn(c, base.relative(side), h, dug);
			}
		}

		// Seitenstollen alle paar Abschnitte (immer auf einer anderen Seite), 2 Hoehe, 3-6 Zellen lang.
		if (tunnel && m.index % 7 == 5) {
			Direction side = sides[(m.index / 7) % 2];
			int len = 3 + r.nextInt(4);
			for (int j = 2; j < 2 + len; j++) {
				if (!carveColumn(c, base.relative(side, j), 2, dug)) {
					break;
				}
			}
		}

		// Stuetzen: Holzpfosten an den Waenden und ein Deckenbalken; dazwischen Fackeln.
		if (tunnel && m.index % 4 == 2) {
			for (Direction side : sides) {
				BlockPos wall = base.relative(side);
				for (int k = 0; k < cells - 1; k++) {
					if (level.getBlockState(wall.above(k)).isAir()) {
						level.setBlock(wall.above(k), Blocks.OAK_FENCE.defaultBlockState(), 3);
					}
				}
			}
			for (int s = -1; s <= 1; s++) {
				BlockPos beam = s == 0 ? base.above(cells) : base.relative(sides[s < 0 ? 0 : 1]).above(cells);
				BlockState there = level.getBlockState(beam);
				if (!there.isAir() && !hazard(level, beam) && !(there.getBlock() instanceof FallingBlock)) {
					level.setBlock(beam, Blocks.OAK_PLANKS.defaultBlockState(), 3);
				}
			}
		} else if (tunnel && m.index % 4 == 0 && m.index > 0) {
			BlockPos spot = base.relative(sides[(m.index / 4) % 2]);
			if (level.getBlockState(spot).isAir() && level.getBlockState(spot.below()).isFaceSturdy(level, spot.below(), Direction.UP)) {
				level.setBlock(spot, Blocks.TORCH.defaultBlockState(), 3);
			}
		}
	}

	/** Grabt eine Saeule der Hoehe h, wenn das gefahrlos geht. false = hier nicht (weitere Zellen dahinter auch nicht). */
	private static boolean carveColumn(Ctx c, BlockPos bottom, int h, List<BlockPos> dug) {
		ServerLevel level = c.level();
		BlockPos floor = bottom.below();
		if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) {
			return false;
		}
		for (int k = 0; k < h; k++) {
			BlockPos p = bottom.above(k);
			if (hazard(level, p)) {
				return false;
			}
		}
		if (level.getBlockState(bottom.above(h)).getBlock() instanceof FallingBlock) {
			return false;
		}
		for (int k = 0; k < h; k++) {
			BlockPos p = bottom.above(k);
			if (breakBlock(c, p)) {
				dug.add(p);
			}
		}
		return true;
	}

	private static boolean breakBlock(Ctx c, BlockPos p) {
		ServerLevel level = c.level();
		BlockState s = level.getBlockState(p);
		if (s.isAir() || !s.getFluidState().isEmpty()) {
			return false;
		}
		List<ItemStack> drops = Block.getDrops(s, level, p, null, c.v(), new ItemStack(Items.IRON_PICKAXE));
		for (ItemStack d : drops) {
			JobUtil.give(level, c.v(), d);
		}
		Anim.blockBreak(level, p, s);
		level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
		return true;
	}

	private static void mineVein(Ctx c, BlockPos start) {
		ServerLevel level = c.level();
		Set<Long> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start);
		seen.add(start.asLong());
		int mined = 0;
		while (!queue.isEmpty() && mined < VEIN_LIMIT) {
			BlockPos p = queue.poll();
			if (!isOre(level.getBlockState(p)) || hazard(level, p)) {
				continue;
			}
			if (breakBlock(c, p)) {
				mined++;
			}
			for (Direction d : Direction.values()) {
				BlockPos n = p.relative(d);
				if (seen.add(n.asLong())) {
					queue.add(n);
				}
			}
		}
	}

	/**
	 * Kleine Luecke im Boden (Trampelpfad, Grasstufe, ein Block Hoehle) mit Bruchstein schliessen statt die Mine aufzugeben.
	 * Echte Hohlraeume (zwei Bloecke frei) bleiben ein Grund zum Schliessen.
	 */
	private static void patchFloor(ServerLevel level, BlockPos floor) {
		BlockState s = level.getBlockState(floor);
		if (s.isFaceSturdy(level, floor, Direction.UP) || !s.getFluidState().isEmpty()) {
			return;
		}
		BlockPos under = floor.below();
		if ((s.isAir() || s.canBeReplaced() || s.is(Blocks.DIRT_PATH) || s.is(Blocks.FARMLAND))
			&& level.getBlockState(under).isFaceSturdy(level, under, Direction.UP)) {
			level.setBlock(floor, Blocks.COBBLESTONE.defaultBlockState(), 3);
		}
	}

	/** Geschlossene Schaechte erhalten; den Eingang erst nach dem Ausstieg aller Lebewesen versperren. */
	public static void tidyOldMines(ServerLevel level, VillageData d) {
		for (VillageData.Mine m : allMines(d)) {
			if (m.done) barricade(level, m, m.index >= descent(m) + MAX_HORIZONTAL);
		}
	}

	/** Archiviert einen abgeschlossenen Schacht genau einmal und behaelt seinen vollstaendigen Datensatz. */
	private static void archiveMine(VillageData d, VillageData.Mine mine) {
		if (d.extra.oldMines.stream().noneMatch(old -> old.entrance.equals(mine.entrance))) {
			d.extra.oldMines.add(mine);
		}
	}

	/** Auch Seitenstollen und noch ungeladene Abschnitte duerfen niemanden hinter der Barrikade einschliessen. */
	static boolean canSeal(ServerLevel level, VillageData.Mine m) {
		List<AABB> passages = new ArrayList<>();
		Direction dir = Direction.from2DDataValue(m.dir);
		AABB bounds = new AABB(m.entrance);
		for (int i = 0; i < Math.max(1, m.index); i++) {
			BlockPos base = bottom(m, i);
			AABB passage = new AABB(base).inflate(1, 0, 1).expandTowards(0, cells(m, i) - 1, 0);
			passages.add(passage);
			bounds = bounds.minmax(passage);
			if (i >= descent(m) && i % 7 == 5) {
				Direction side = (i / 7) % 2 == 0 ? dir.getCounterClockWise() : dir.getClockWise();
				AABB branch = new AABB(base.relative(side, 2)).minmax(new AABB(base.relative(side, 7))).expandTowards(0, 1, 0);
				passages.add(branch);
				bounds = bounds.minmax(branch);
			}
		}
		for (int x = net.minecraft.util.Mth.floor(bounds.minX) >> 4; x <= net.minecraft.util.Mth.floor(bounds.maxX) >> 4; x++) {
			for (int z = net.minecraft.util.Mth.floor(bounds.minZ) >> 4; z <= net.minecraft.util.Mth.floor(bounds.maxZ) >> 4; z++) {
				if (!level.hasChunkAt(new BlockPos(x << 4, m.entrance.getY(), z << 4))) return false;
			}
		}
		return level.getEntitiesOfClass(LivingEntity.class, bounds, e -> e.isAlive()
			&& passages.stream().anyMatch(area -> area.intersects(e.getBoundingBox()))).isEmpty();
	}

	private static void finishMine(Ctx c, VillageRef ref, VillageData.Mine m, String message) {
		m.done = true;
		archiveMine(ref.data(), m);
		if (ref.data().mine.filter(active -> active == m).isPresent()) {
			ref.data().mine = Optional.empty();
		}
		ref.dirty();
		barricade(c.level(), m, message.contains("ausgebeutet"));
		c.st().mineCheckAt = c.now() + 12000;
		org.slf4j.LoggerFactory.getLogger("villagerplus").info("Villagers: Repurposed: Mine bei {} beendet bei Abschnitt {}: {}", m.entrance, m.index, message);
		JobUtil.tellNearbyPlayers(c.level(), c.v(), 48, Component.literal(message));
		c.level().playSound(null, m.entrance, SoundEvents.ANVIL_PLACE, SoundSource.BLOCKS, 0.5F, 1.0F);
	}

	/** Zum Ueberprufen der Mine von aussen (z.B. Statuskommando). */
	public static String describe(VillageData d) {
		if (d.mine.isEmpty()) {
			return "keine";
		}
		VillageData.Mine m = d.mine.get();
		return (m.done ? "beendet" : "aktiv") + " bei " + m.entrance.toShortString() + ", Abschnitt " + m.index;
	}

	// --- Wege in der Mine -----------------------------------------------------------------------

	/** Index des naechsten Stollenabschnitts (ab dem Eingang) in Reichweite, sonst -1. */
	private static int nearest(VillageData.Mine m, Vec3 pos) {
		// Ohne Objekt-Erzeugung gerechnet (lief fuer jeden Bergmann bei jedem Takt ueber bis zu ~80 Abschnitte).
		int best = -1;
		double bestDist = 2.6 * 2.6;
		Direction dir = Direction.from2DDataValue(m.dir);
		double ex = m.entrance.getX() + 0.5;
		double ez = m.entrance.getZ() + 0.5;
		int ey = m.entrance.getY();
		for (int i = 0; i <= m.index; i++) {
			double dx = pos.x - (ex + dir.getStepX() * i);
			double dz = pos.z - (ez + dir.getStepZ() * i);
			double dy = pos.y - Math.max(m.targetY, ey - i);
			double d = dx * dx + dy * dy + dz * dz;
			if (d < bestDist) {
				best = i;
				bestDist = d;
			}
		}
		return best;
	}

	static boolean teleport(Ctx c, BlockPos cell) {
		if (!safeCell(c, cell)) {
			return false;
		}
		c.v().getNavigation().stop();
		c.v().teleportTo(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5);
		c.v().resetFallDistance();
		c.level().playSound(null, cell, SoundEvents.STONE_STEP, SoundSource.NEUTRAL, 0.5F, 1.0F);
		return true;
	}

	/** Alle bekannten Minen des Dorfes (aktuelle zuerst). */
	private static List<VillageData.Mine> allMines(VillageData d) {
		List<VillageData.Mine> out = new ArrayList<>();
		d.mine.ifPresent(out::add);
		out.addAll(d.extra.oldMines);
		return out;
	}

	/** Die Mine, in deren Stollen der Dorfbewohner gerade steckt (null = keine). */
	private static VillageData.Mine mineAround(Ctx c) {
		if (c.village() == null) {
			return null;
		}
		for (VillageData.Mine m : allMines(c.village().data())) {
			double reach = m.index + 10.0;
			if (c.v().position().distanceToSqr(Vec3.atBottomCenterOf(m.entrance)) > reach * reach) {
				continue;
			}
			if (nearest(m, c.v().position()) >= 1 && c.v().getY() < m.entrance.getY() - 0.5) {
				return m;
			}
		}
		return null;
	}

	/** Steckt der Dorfbewohner gerade im Stollen (unter Tage), ohne dort zu arbeiten? */
	public static boolean insideMine(Ctx c) {
		if (c.now() < c.st().miningUntil) {
			return false;
		}
		return mineAround(c) != null;
	}

	/** Fester Boden unter der Zelle und keine Fluessigkeit in der Zelle selbst. */
	static boolean safeCell(Ctx c, BlockPos cell) {
		if (!c.level().hasChunkAt(cell) || !solidFloor(c, cell)
			|| !c.level().getBlockState(cell).getFluidState().isEmpty()
			|| !c.level().getBlockState(cell.above()).getFluidState().isEmpty()) {
			return false;
		}
		double x = cell.getX() + 0.5;
		double y = cell.getY();
		double z = cell.getZ() + 0.5;
		AABB destination = c.v().getBoundingBox().move(x - c.v().getX(), y - c.v().getY(), z - c.v().getZ());
		return c.level().noCollision(c.v(), destination);
	}

	private static boolean solidFloor(Ctx c, BlockPos cell) {
		BlockPos f = cell.below();
		return c.level().getBlockState(f).isFaceSturdy(c.level(), f, net.minecraft.core.Direction.UP);
	}

	/** Holt einen Dorfbewohner aus dem Stollen, wenn er nicht (mehr) als Bergmann arbeitet. */
	public static void exitStep(Ctx c) {
		VillageData.Mine m = mineAround(c);
		if (m == null) {
			return;
		}
		int k = nearest(m, c.v().position());
		if (k >= 1) {
			if (!teleport(c, bottom(m, k - 1)) && !exitToSurface(c, m)) {
				return;
			}
			c.st().cooldownUntil = c.now() + 8;
		}
	}

	/** Freier oberirdischer Ausstieg fuer einen blockierten/veraenderten Stollen. */
	private static boolean exitToSurface(Ctx c, VillageData.Mine mine) {
		BlockPos origin = mine.entrance;
		for (int radius = 0; radius <= 6; radius++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
					int x = origin.getX() + dx;
					int z = origin.getZ() + dz;
					if (!c.level().hasChunkAt(new BlockPos(x, origin.getY(), z))) continue;
					BlockPos feet = new BlockPos(x, c.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
					if (teleport(c, feet)) return true;
				}
			}
		}
		return false;
	}
}
