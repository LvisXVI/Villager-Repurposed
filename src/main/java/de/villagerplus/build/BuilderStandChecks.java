package de.villagerplus.build;

import de.villagerplus.VillagerPlus;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobState;
import de.villagerplus.job.JobUtil;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Actual navigation queries and builder transactions; no AI ticking or physical arrival is claimed. */
public final class BuilderStandChecks {
	private BuilderStandChecks() { }
	private static final int FLAGS = 2 | 16 | 32;
	private record Plan(String type, Optional<BlockPos> origin, int dir, int step, int variant,
		Optional<BlockPos> source, long progress, long nextBuild, List<String> wanted) {
		static Plan of(VillageData d) { return new Plan(d.planType,d.planOrigin,d.planDir,d.planStep,d.planVariant,
			d.planSource,d.planProgress,d.nextBuild,List.copyOf(d.wanted)); }
		void restore(VillageData d) {
			d.planType=type;d.planOrigin=origin;d.planDir=dir;d.planStep=step;d.planVariant=variant;
			d.planSource=source;d.planProgress=progress;d.nextBuild=nextBuild;d.wanted.clear();d.wanted.addAll(wanted);
		}
	}

	@SuppressWarnings("unchecked")
	public static int run(ServerLevel level, VillageRef ref) throws Exception {
		VillageData d=ref.data(); Plan previous=Plan.of(d);
		Container box=ref.chest(level);
		if(box==null)throw new IllegalStateException("BUILDER STAND CHECK FAILED: fixture chest missing");
		List<ItemStack> stock=new ArrayList<>();for(int i=0;i<box.getContainerSize();i++)stock.add(box.getItem(i).copy());
		var oldLager=d.extra.lager;var oldMore=new ArrayList<>(d.extra.lagerMore);var oldChests=new ArrayList<>(d.extra.lagerChests);
		var oldDemolition=ref.bellEntity().getAttached(VillagerPlus.DEMOLITION);
		var oldConstruction=ref.bellEntity().getAttached(VillagerPlus.CONSTRUCTION);
		BlockPos floor=ref.bell().offset(65,0,65), origin=floor.above(13), preferred=floor.east(5);
		Map<BlockPos,BlockState> world=new LinkedHashMap<>();
		Map<String,Map<Long,Object>> caches=new LinkedHashMap<>();Map<String,Object> cacheValues=new LinkedHashMap<>();
		for(String name:List.of("STEPS","FOOT","PREP_RECORD")) {
			var field=Builder.class.getDeclaredField(name);field.setAccessible(true);
			Map<Long,Object> map=(Map<Long,Object>)field.get(null);caches.put(name,map);cacheValues.put(name,map.get(origin.asLong()));
		}
		Villager worker=null;int passed=0;
		try {
			// Every touched column is on the runner's remote test plateau, and restored even after a failed assertion.
			for(int x=-8;x<=72;x++)for(int z=-8;z<=8;z++)for(int y=-1;y<=16;y++) {
				BlockPos p=floor.offset(x,y,z);level.getChunkAt(p);
				if(level.getBlockEntity(p)!=null)throw new IllegalStateException("BUILDER STAND CHECK FAILED: fixture overlaps block entity");
				world.put(p,level.getBlockState(p));level.setBlock(p,y==-1?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),FLAGS);
			}
			d.extra.lager=Optional.empty();d.extra.lagerMore.clear();d.extra.lagerChests.clear();
			ref.bellEntity().removeAttached(VillagerPlus.DEMOLITION);ref.bellEntity().removeAttached(VillagerPlus.CONSTRUCTION);
			worker=EntityTypes.VILLAGER.spawn(level,preferred,EntitySpawnReason.COMMAND);
			if(worker==null)throw new IllegalStateException("BUILDER STAND CHECK FAILED: worker spawn");
			worker.addTag("vp090_fixture");worker.setNoAi(true);worker.setInvulnerable(true);
			worker.setPos(preferred.getX()+0.5,preferred.getY(),preferred.getZ()+0.5);
			long now=Math.max(1,level.getGameTime());JobState st=new JobState();
			Ctx c=new Ctx(level,worker,st,worker.getAttachedOrCreate(VillagerPlus.MEMORY),ref,now);
			// Repeated steps keep the test away from finish(), while still paying for exactly one real placement.
			List<Step> steps=java.util.Collections.nCopies(100,Step.block(origin,Blocks.COBBLESTONE));
			caches.get("STEPS").put(origin.asLong(),steps);caches.get("FOOT").remove(origin.asLong());caches.get("PREP_RECORD").remove(origin.asLong());
			d.planType=Blueprints.TOOLSHED;d.planOrigin=Optional.of(origin);d.planDir=Direction.EAST.get2DDataValue();
			d.planStep=0;d.planVariant=0;d.planSource=Optional.empty();d.planProgress=now;d.wanted.clear();
			box.clearContent();box.setItem(0,new ItemStack(Items.COBBLESTONE,64));box.setItem(1,new ItemStack(Items.COBBLESTONE,64));
			BlockPos distant=floor.east(64);
			worker.setPos(distant.getX()+0.5,distant.getY(),distant.getZ()+0.5);worker.setOnGround(true);
			var defaultPath=worker.getNavigation().createPath(preferred,0);
			check(defaultPath==null||!defaultPath.canReach(),"default navigation cannot fully reach a stand 59 blocks away");passed++;
			check(preferred.equals(Builder.workStand(c,origin,Direction.EAST,steps)),
				"bounded construction search finds a real route from 59 blocks away");passed++;
			var distantBefore=worker.position();Builder.tick(c,true);
			var walk=worker.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
			check(walk.isPresent()&&d.planStep==0&&JobUtil.count(box,s->s.is(Items.COBBLESTONE))==128
				&&worker.position().equals(distantBefore)
				&&worker.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(walk.get().getTarget().currentBlockPosition()))<=100.0,
				"distant builder receives a short route waypoint without advancing, paying or teleporting");passed++;
			var waypointPath=worker.getNavigation().createPath(walk.orElseThrow().getTarget().currentBlockPosition(),0);
			check(waypointPath!=null&&waypointPath.canReach(),"vanilla WALK_TARGET recomputation can reach the route waypoint");passed++;
			worker.getNavigation().stop();worker.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
			worker.setPos(preferred.getX()+0.5,preferred.getY(),preferred.getZ()+0.5);
			BlockPos stand=Builder.workStand(c,origin,Direction.EAST,steps);
			check(preferred.equals(stand)&&stand.getY()==origin.getY()-13&&Builder.safeWorkFloor(level,stand),
				"frontage uses supported local terrain thirteen blocks below the building anchor: stand="+stand+", expected="+preferred+", safe="+(stand!=null&&Builder.safeWorkFloor(level,stand)));passed++;
			var before=worker.position();Builder.tick(c,true);
			check(d.planStep>0&&level.getBlockState(origin).is(Blocks.COBBLESTONE)
				&&JobUtil.count(box,s->s.is(Items.COBBLESTONE))==127&&worker.position().equals(before),
				"real builder tick advances on uneven frontage, pays one placement and does not teleport");passed++;

			level.setBlock(preferred.below(),Blocks.WATER.defaultBlockState(),FLAGS);
			BlockPos alternative=Builder.workStand(c,origin,Direction.EAST,steps);
			check(alternative!=null&&!alternative.equals(preferred)&&Builder.safeWorkFloor(level,alternative)
				&&!(alternative.getX()==origin.getX()&&alternative.getZ()==origin.getZ()),
				"wet preferred frontage falls back to a reachable clear perimeter position");passed++;
			level.setBlock(preferred.below(),Blocks.STONE.defaultBlockState(),FLAGS);
			level.setBlock(preferred.above(),Blocks.STONE.defaultBlockState(),FLAGS);
			check(!Builder.safeWorkFloor(level,preferred),"head collision rejects a standing position");passed++;
			level.setBlock(preferred.above(),Blocks.AIR.defaultBlockState(),FLAGS);
			level.setBlock(preferred.below(),Blocks.AIR.defaultBlockState(),FLAGS);
			check(!Builder.safeWorkFloor(level,preferred),"unsupported feet reject a standing position");passed++;

			for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)
				level.setBlock(floor.offset(x,-1,z),Blocks.WATER.defaultBlockState(),FLAGS);
			d.planStep=0;int count=JobUtil.count(box,s->s.is(Items.COBBLESTONE));
			check(Builder.workStand(c,origin,Direction.EAST,steps)==null,"all wet perimeter candidates yield no work stand");passed++;
			level.setBlock(preferred.above(8),Blocks.STONE.defaultBlockState(),FLAGS);
			check(Builder.safeWorkFloor(level,preferred.above(9))&&Builder.workStand(c,origin,Direction.EAST,steps)==null,
				"a clear dry supported but unreachable high pillar is rejected by real navigation");passed++;
			Builder.tick(new Ctx(level,worker,st,c.mem(),ref,now+1),true);
			check(d.planStep==0&&JobUtil.count(box,s->s.is(Items.COBBLESTONE))==count&&worker.position().equals(before)
				&&st.cooldownUntil==now+41,"no safe stand preserves cursor, materials and worker position with bounded retry cooldown");passed++;
			return passed;
		} finally {
			if(worker!=null){worker.discard();de.villagerplus.village.Villages.forget(worker.getUUID());}
			world.forEach((p,state)->level.setBlock(p,state,FLAGS));
			box.clearContent();for(int i=0;i<stock.size();i++)box.setItem(i,stock.get(i));
			previous.restore(d);d.extra.lager=oldLager;d.extra.lagerMore.clear();d.extra.lagerMore.addAll(oldMore);
			d.extra.lagerChests.clear();d.extra.lagerChests.addAll(oldChests);
			if(oldDemolition==null)ref.bellEntity().removeAttached(VillagerPlus.DEMOLITION);else ref.bellEntity().setAttached(VillagerPlus.DEMOLITION,oldDemolition);
			if(oldConstruction==null)ref.bellEntity().removeAttached(VillagerPlus.CONSTRUCTION);else ref.bellEntity().setAttached(VillagerPlus.CONSTRUCTION,oldConstruction);
			caches.forEach((name,map)->{Object old=cacheValues.get(name);if(old==null)map.remove(origin.asLong());else map.put(origin.asLong(),old);});
			ref.dirty();
		}
	}
	private static void check(boolean condition,String message) {
		if(!condition)throw new IllegalStateException("BUILDER STAND CHECK FAILED: "+message);
	}
}
