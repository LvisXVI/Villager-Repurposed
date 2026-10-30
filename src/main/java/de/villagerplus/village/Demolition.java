package de.villagerplus.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.build.Builder;
import de.villagerplus.build.Built;
import de.villagerplus.build.Furnisher;
import de.villagerplus.build.Step;
import de.villagerplus.job.Anim;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Abriss: Ein Maurer traegt ein selbst gebautes Gebaeude Block fuer Block von oben nach unten ab - mit sichtbaren Rissstufen
 * 0-9, Werkzeugschwung und Partikeln. Baumaterial und Kisteninhalt wandern in die Dorfkiste; das Gelaende wird ebnet und mit der
 * Oberflaeche der Umgebung renaturiert. Ausgeloest ueber die Karte der Dorftafel (Gebaeude anklicken -> Abriss).
 */
public final class Demolition {
	public record Job(BlockPos origin, int index) {
		public static final Codec<Job> CODEC = RecordCodecBuilder.create(i -> i.group(
			BlockPos.CODEC.fieldOf("origin").forGetter(Job::origin), Codec.INT.optionalFieldOf("index", 0).forGetter(Job::index)).apply(i, Job::new));
	}

	private record Key(ServerLevel level, BlockPos pos) {}
	private record Claim(java.util.UUID owner, long touchedAt) {}
	private static final Map<Key, List<Step>> STEPS = new HashMap<>();
	private static final Map<Key, Claim> CLAIMS = new HashMap<>();
	static {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) ->
			CLAIMS.entrySet().removeIf(e -> e.getKey().level() == level && e.getValue().owner().equals(entity.getUUID())));
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			STEPS.clear();
			CLAIMS.clear();
		});
	}

	private Demolition() {
	}

	public static List<Job> jobs(VillageRef ref) {
		return ref.bellEntity().getAttachedOrElse(VillagerPlus.DEMOLITION, List.of());
	}

	private static void save(VillageRef ref, List<Job> jobs) {
		ref.bellEntity().setAttached(VillagerPlus.DEMOLITION, List.copyOf(jobs));
		ref.dirty();
	}

	private static boolean allowed(String type) {
		return type.equals(Blueprints.HOUSE) || type.equals(Blueprints.SCHOOL) || Blueprints.special(type);
	}

	public static String label(String type) {
		return switch (type) {
			case Blueprints.HOUSE -> "Wohnhaus";
			case Blueprints.SCHOOL -> "Dorfschule";
			case Blueprints.GRANARY -> "Kornspeicher";
			case Blueprints.TOOLSHED -> "Werkzeugkammer";
			case Blueprints.KITCHEN -> "Dorfküche";
			case Blueprints.STATION -> "Bahnhof";
			default -> type;
		};
	}

	/** Zeilen fuer die Tafel: {@code @o|x|z} (Rasterursprung) und {@code @b|typ|name|x|y|z|abriss} je abreissbarem Gebaeude. */
	public static List<String> mapRows(ServerLevel level, VillageRef ref) {
		List<String> rows = new ArrayList<>();
		int[] o = VillageMap.gridOrigin(level, ref);
		rows.add("@o|" + o[0] + "|" + o[1]);
		var queued = jobs(ref);
		for (Built b : Builder.builtOf(ref)) {
			if (allowed(b.type())) {
				boolean running = queued.stream().anyMatch(j -> j.origin().equals(b.origin()));
				rows.add("@b|" + b.type() + "|" + label(b.type()) + "|" + b.origin().getX() + "|" + b.origin().getY() + "|" + b.origin().getZ() + "|" + (running ? "1" : "0"));
			}
		}
		return rows;
	}

	/** Abriss anordnen (Gebaeude in der Naehe von {@code at}). Meldet den Grund bei Ablehnung. */
	public static String request(ServerLevel level, VillageRef ref, BlockPos at) {
		Built found = null;
		for (Built b : Builder.builtOf(ref)) {
			if (allowed(b.type()) && Math.abs(b.origin().getX() - at.getX()) <= 3 && Math.abs(b.origin().getZ() - at.getZ()) <= 3) {
				found = b;
			}
		}
		if (found == null) {
			return "Dort steht kein abreißbares Gebäude des Dorfes.";
		}
		final Built target = found;
		List<Job> list = new ArrayList<>(jobs(ref));
		if (list.stream().anyMatch(j -> j.origin().equals(target.origin()))) {
			return "Der Abriss läuft schon.";
		}
		list.add(new Job(target.origin(), 0));
		save(ref, list);
		Journal.add(level, ref, "Bauen", "Dorftafel", "Abriss angeordnet: " + label(target.type()), 0);
		return "Abriss angeordnet: " + label(target.type()) + ". Ein Maurer trägt es Block für Block ab.";
	}

	/** Arbeit des Maurers am Abriss (true = beschaeftigt). */
	public static boolean work(Ctx c, VillageRef ref) {
		List<Job> list = new ArrayList<>(jobs(ref));
		if (list.isEmpty()) {
			Anim.cancelBreaking(c.v());
			return false;
		}
		ServerLevel level = c.level();
		Job job = list.get(0);
		Built b = Builder.builtOf(ref).stream().filter(x -> x.origin().equals(job.origin())).findFirst().orElse(null);
		if (b == null || !level.hasChunkAt(job.origin())) {
			clearSite(level, job.origin());
			if (b == null) {
				list.remove(0);
				save(ref, list);
			}
			return false;
		}
		List<Step> steps = STEPS.computeIfAbsent(new Key(level, job.origin()), k -> {
			List<Step> all = new ArrayList<>(Builder.stepsOfBuilt(level, ref, b, c.v()));
			all.sort(Comparator.comparingInt((Step s) -> s.pos().getY()).reversed());
			return all;
		});
		int i = job.index();
		while (i < steps.size()) {
			if (!level.hasChunkAt(steps.get(i).pos())) {
				Anim.cancelBreaking(c.v());
				return true;
			}
			BlockState st = level.getBlockState(steps.get(i).pos());
			if (st.isAir() || !st.getFluidState().isEmpty()) {
				i++;
			} else {
				break;
			}
		}
		if (i >= steps.size()) {
			finish(c, ref, b, steps);
			list.remove(0);
			save(ref, list);
			return true;
		}
		// Parallelarbeit: jeder Maurer reserviert sich einen eigenen, von keinem anderen beanspruchten Block (Reservierung verfaellt nach 3 s).
		BlockPos pos = null;
		BlockPos available = null;
		java.util.UUID me = c.v().getUUID();
		CLAIMS.entrySet().removeIf(e -> {
			if (e.getKey().level() != level || c.now() - e.getValue().touchedAt() <= 60) return false;
			if (level.getEntity(e.getValue().owner()) instanceof net.minecraft.world.entity.npc.villager.Villager owner) Anim.cancelBreaking(owner);
			return true;
		});
		for (int j = i, seen = 0; j < steps.size() && seen < 60; j++) {
			BlockPos cand = steps.get(j).pos();
			if (!level.hasChunkAt(cand)) continue;
			BlockState cs = level.getBlockState(cand);
			if (cs.isAir() || !cs.getFluidState().isEmpty()) {
				continue;
			}
			seen++;
			Claim claim = CLAIMS.get(new Key(level, cand));
			if (claim != null && claim.owner().equals(me)) {
				pos = cand;
				break;
			}
			if (claim == null && available == null) available = cand;
		}
		if (pos == null) pos = available;
		if (pos == null) {
			Anim.cancelBreaking(c.v());
			return true; // All candidates belong to other masons; never steal their crack progress.
		}
		CLAIMS.put(new Key(level, pos), new Claim(me, c.now()));
		BlockState state = level.getBlockState(pos);
		c.setActivity("reißt " + label(b.type()) + " ab");
		// Der Maurer steht am Boden unter dem Block (Dachfirst liegt hoch ueber ihm); gezaehlt wird nur die Entfernung in der Ebene.
		if (!JobUtil.approach(c, new BlockPos(pos.getX(), b.origin().getY(), pos.getZ()), 4.0)) {
			Anim.cancelBreaking(c.v());
			return true;
		}
		if (!Anim.breakStep(c, pos, state, demolitionTool(state))) {
			c.st().cooldownUntil = c.now() + 6;
		} else {
			CLAIMS.remove(new Key(level, pos));
			collect(level, ref, pos, state);
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
			level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK, state),
				pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 10, 0.3, 0.3, 0.3, 0.05);
			level.playSound(null, pos, state.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0F, 0.9F);
			// A parallel worker may have removed a later step. Never skip the still occupied cursor block.
			if (pos.equals(steps.get(i).pos())) i++;
			c.mem().jobsDone++;
			c.st().cooldownUntil = c.now() + 4;
		}
		list.set(0, new Job(job.origin(), i));
		save(ref, list);
		return true;
	}

	private static net.minecraft.world.item.Item demolitionTool(BlockState state) {
		if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE)) return Items.IRON_AXE;
		if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL)) return Items.IRON_SHOVEL;
		if (state.is(net.minecraft.tags.BlockTags.WOOL)) return Items.SHEARS;
		return Items.IRON_PICKAXE;
	}

	private static void clearSite(ServerLevel level, BlockPos origin) {
		List<Step> steps = STEPS.remove(new Key(level, origin));
		if (steps == null) return;
		for (Step step : steps) {
			Claim claim = CLAIMS.remove(new Key(level, step.pos()));
			if (claim != null && level.getEntity(claim.owner()) instanceof net.minecraft.world.entity.npc.villager.Villager owner) Anim.cancelBreaking(owner);
		}
	}

	/** Drops und Kisteninhalt gehen in die Dorfkiste (sonst auf den Boden). */
	private static void collect(ServerLevel level, VillageRef ref, BlockPos pos, BlockState state) {
		List<Container> communal = Balance.storage(level, ref);
		BlockEntity be = level.getBlockEntity(pos);
		List<ItemStack> out = new ArrayList<>();
		if (be instanceof Container cont) {
			for (int k = 0; k < cont.getContainerSize(); k++) {
				if (!cont.getItem(k).isEmpty()) {
					out.add(cont.getItem(k).copy());
				}
			}
			cont.clearContent();
		}
		out.addAll(Block.getDrops(state, level, pos, be));
		for (ItemStack drop : out) {
			ItemStack rest = StockPolicy.insertVillage(level, ref, drop);
			if (!rest.isEmpty()) {
				Block.popResource(level, pos, rest);
			}
		}
	}

	/** Abschluss: Grube auffuellen, Oberflaeche der Umgebung uebernehmen, Registrierung austragen. */
	private static void finish(Ctx c, VillageRef ref, Built b, List<Step> steps) {
		ServerLevel level = c.level();
		var box = Furnisher.boxOf(steps);
		BlockState surface = Blocks.GRASS_BLOCK.defaultBlockState();
		int[][] probes = {{box.minX() - 3, box.minZ()}, {box.maxX() + 3, box.minZ()}, {box.minX(), box.maxZ() + 3}, {box.maxX(), box.minZ() - 3}};
		for (int[] pr : probes) {
			BlockPos probe = new BlockPos(pr[0], b.origin().getY(), pr[1]);
			if (level.hasChunkAt(probe)) {
				BlockState s = level.getBlockState(new BlockPos(pr[0], level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pr[0], pr[1]) - 1, pr[1]));
				if (VillageStyleNatural(s)) {
					surface = s;
					break;
				}
			}
		}
		int floorY = b.origin().getY() - 1;
		for (int x = box.minX(); x <= box.maxX(); x++) {
			for (int z = box.minZ(); z <= box.maxZ(); z++) {
				for (int y = floorY - 3; y < floorY; y++) {
					BlockPos p = new BlockPos(x, y, z);
					BlockState cur = level.getBlockState(p);
					if (cur.isAir() || cur.canBeReplaced() || !VillageStyleNatural(cur)) {
						level.setBlock(p, Blocks.DIRT.defaultBlockState(), 2);
					}
				}
				BlockPos top = new BlockPos(x, floorY, z);
				BlockState cur = level.getBlockState(top);
				if (!VillageStyleNatural(cur) || cur.is(Blocks.DIRT_PATH)) {
					level.setBlock(top, surface, 2);
				}
			}
		}
		Builder.forget(ref, b);
		VillageData d = ref.data();
		if (b.type().equals(Blueprints.HOUSE)) {
			d.houses = Math.max(0, d.houses - 1);
		} else if (b.type().equals(Blueprints.SCHOOL)) {
			d.extra.school = java.util.Optional.empty();
		} else if (Blueprints.special(b.type())) {
			Specials.remove(ref, b.type());
		}
		d.nextBuild = c.now() + 600;
		ref.dirty();
		clearSite(level, b.origin());
		Journal.add(level, ref, "Bauen", c.mem().name, label(b.type()) + " abgerissen, Gelände renaturiert", 0);
		JobUtil.tellNearbyPlayers(level, c.v(), 40, net.minecraft.network.chat.Component.literal("Abriss fertig: " + label(b.type()) + " – Material liegt in der Dorfkiste."));
	}

	private static boolean VillageStyleNatural(BlockState s) {
		return VillageStyle.natural(s);
	}
}
