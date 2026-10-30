package de.villagerplus.job;

import de.villagerplus.village.Market;
import de.villagerplus.village.Network;
import de.villagerplus.village.TradeNetwork;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;

/** Patrols the village center and the built, local halves of registered trade roads; rings an alarm on hostile sighting. */
public final class GuardJob implements Job {
	@Override
	public boolean primary(Ctx c) { return patrol(c); }

	@Override
	public boolean secondary(Ctx c) { return patrol(c); }

	private static boolean patrol(Ctx c) {
		if (c.village() == null) return false;
		ServerLevel level = c.level();
		var ref = c.village();
		if (c.now() >= c.st().lightAt) {
			var hostiles = level.getEntitiesOfClass(Monster.class, c.v().getBoundingBox().inflate(24), Monster::isAlive);
			if (!hostiles.isEmpty() && level.getBlockEntity(ref.bell()) instanceof BellBlockEntity bell) {
				bell.onHit(Direction.UP);
				c.st().lightAt = c.now() + 200;
				c.setActivity("schlägt bei Gefahr Alarm");
				return true;
			}
		}

		List<BlockPos> points = watchpoints(level, ref);
		if (points.isEmpty()) return false;
		int index = Math.floorMod(c.st().patrolIndex, points.size());
		BlockPos target = points.get(index);
		c.st().target = target;
		c.setActivity("patrouilliert die Dorfwege");
		if (!JobUtil.approach(c, target, 2.5)) return true;
		c.st().patrolIndex = (index + 1) % points.size();
		c.st().clearTarget();
		c.st().cooldownUntil = c.now() + 40;
		c.mem().jobsDone++;
		return true;
	}

	private static List<BlockPos> watchpoints(ServerLevel level, de.villagerplus.village.VillageRef ref) {
		List<BlockPos> points = new ArrayList<>();
		BlockPos center = Market.of(ref).plaza().orElse(ref.bell());
		points.add(walkPoint(level, center));
		points.add(walkPoint(level, ref.bell()));
		String dimension = Network.dim(level);
		for (TradeNetwork.Link link : Network.of(ref.bellEntity()).links()) {
			if ((!link.route() && link.relation() < de.villagerplus.village.Roads.TRADE_PARTNER) || !link.dimension().equals(dimension)) continue;
			int dx = link.bell().getX() - ref.bell().getX();
			int dz = link.bell().getZ() - ref.bell().getZ();
			int length = Math.max(Math.abs(dx), Math.abs(dz));
			if (length == 0) continue;
			// Patrol just the village-owned half of the registered route.
			for (int fraction : new int[] {12, 28, 44}) {
				int along = Math.min(length / 2, length * fraction / 100);
				BlockPos p = ref.bell().offset((int) Math.round(dx * along / (double) length), 0, (int) Math.round(dz * along / (double) length));
				if (level.hasChunkAt(p)) points.add(walkPoint(level, p));
			}
		}
		return points.stream().filter(p -> level.hasChunkAt(p)).distinct().toList();
	}

	private static BlockPos walkPoint(ServerLevel level, BlockPos pos) {
		return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos);
	}
}
