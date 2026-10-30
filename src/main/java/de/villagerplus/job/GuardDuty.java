package de.villagerplus.job;

import de.villagerplus.build.Blueprints;
import de.villagerplus.village.Market;
import de.villagerplus.village.Network;
import de.villagerplus.village.Specials;
import de.villagerplus.village.TradeNetwork;
import de.villagerplus.village.VillageRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;

/** Wache, Ausguck und Abenteurer: Türme, Straßen, Bahn, Kampf und Raid-Alarm. */
public final class GuardDuty {
	private GuardDuty() { }

	public static boolean work(Ctx c) {
		if (c.village() == null) return false;
		if (alarm(c)) return true;
		if (fight(c)) return true;
		List<BlockPos> points = posts(c.level(), c.village());
		if (points.isEmpty()) return false;
		int index = Math.floorMod(c.st().patrolIndex, points.size());
		BlockPos target = points.get(index);
		c.st().target = target;
		c.setActivity("patrouilliert Straßen und Bahn");
		Anim.hold(c, Items.IRON_SWORD);
		if (!JobUtil.approach(c, target, 2.5)) return true;
		c.st().patrolIndex = (index + 1) % points.size();
		c.st().clearTarget();
		c.st().cooldownUntil = c.now() + 40;
		c.mem().jobsDone++;
		return true;
	}

	public static boolean raidNearby(ServerLevel level, BlockPos bell) {
		var raids = level.getRaids();
		return raids != null && raids.getNearbyRaid(bell, 96) != null;
	}

	public static List<BlockPos> posts(ServerLevel level, VillageRef ref) {
		List<BlockPos> points = new ArrayList<>();
		BlockPos center = Market.of(ref).plaza().orElse(ref.bell());
		points.add(walk(level, center));
		points.add(walk(level, ref.bell()));
		for (BlockPos tower : towers(level, ref.bell())) points.add(walk(level, tower));
		BlockPos station = Specials.get(ref, Blueprints.STATION);
		if (station != null && level.hasChunkAt(station)) points.add(walk(level, station));
		String dimension = Network.dim(level);
		for (TradeNetwork.Link link : Network.of(ref.bellEntity()).links()) {
			if ((!link.route() && link.relation() < de.villagerplus.village.Roads.TRADE_PARTNER) || !link.dimension().equals(dimension)) continue;
			int dx = link.bell().getX() - ref.bell().getX();
			int dz = link.bell().getZ() - ref.bell().getZ();
			int length = Math.max(Math.abs(dx), Math.abs(dz));
			if (length == 0) continue;
			for (int fraction : new int[] {12, 28, 44}) {
				int along = Math.min(length / 2, length * fraction / 100);
				BlockPos point = ref.bell().offset((int) Math.round(dx * along / (double) length), 0, (int) Math.round(dz * along / (double) length));
				if (level.hasChunkAt(point)) points.add(walk(level, point));
			}
		}
		return points.stream().filter(point -> level.hasChunkAt(point)).distinct().toList();
	}

	private static boolean alarm(Ctx c) {
		boolean raid = raidNearby(c.level(), c.village().bell());
		boolean hostile = !c.level().getEntitiesOfClass(Monster.class, c.v().getBoundingBox().inflate(24), Monster::isAlive).isEmpty();
		if ((!raid && !hostile) || c.now() < c.st().lightAt) return false;
		if (c.level().getBlockEntity(c.village().bell()) instanceof BellBlockEntity bell) bell.onHit(Direction.UP);
		c.st().lightAt = c.now() + 200;
		c.setActivity(raid ? "läutet Raid-Alarm" : "schlägt bei Gefahr Alarm");
		return true;
	}

	private static boolean fight(Ctx c) {
		Monster hostile = c.st().targetEntity instanceof Monster monster && monster.isAlive() ? monster
			: c.level().getEntitiesOfClass(Monster.class, c.v().getBoundingBox().inflate(16), Monster::isAlive).stream()
				.min(Comparator.comparingDouble(monster -> monster.distanceToSqr(c.v()))).orElse(null);
		if (hostile == null) {
			if (c.st().targetEntity instanceof Monster) c.st().clearTarget();
			return false;
		}
		c.st().targetEntity = hostile;
		c.setActivity("bekämpft ein Monster");
		Anim.hold(c, Items.IRON_SWORD);
		if (!JobUtil.approach(c, hostile.position(), 2.4)) return true;
		hostile.hurtServer(c.level(), c.level().damageSources().mobAttack(c.v()), 6.0F);
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		if (!hostile.isAlive()) c.st().clearTarget();
		c.st().cooldownUntil = c.now() + 20;
		return true;
	}

	private static List<BlockPos> towers(ServerLevel level, BlockPos bell) {
		List<BlockPos> towers = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(bell.offset(-24, 3, -24), bell.offset(24, 16, 24))) {
			if (!level.getBlockState(pos).is(Blocks.LANTERN)) continue;
			BlockPos post = pos.below();
			if (level.getBlockState(post).is(Blocks.OAK_FENCE) || level.getBlockState(post).is(Blocks.SPRUCE_FENCE)) towers.add(post.immutable());
		}
		return towers;
	}

	private static BlockPos walk(ServerLevel level, BlockPos pos) {
		if (!level.hasChunkAt(pos)) return pos;
		return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos);
	}
}
