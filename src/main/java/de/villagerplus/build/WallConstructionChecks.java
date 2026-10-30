package de.villagerplus.build;

import de.villagerplus.VillagerPlus;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobManager;
import de.villagerplus.job.JobUtil;
import de.villagerplus.village.Anchor;
import de.villagerplus.village.Development;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageExtent;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.VillageStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Real builder transactions on the development runner's remote plateau, with all touched fixture state restored. */
public final class WallConstructionChecks {
	private WallConstructionChecks() { }
	private static final int FLAGS = 2 | 16 | 32;
	private record Plan(String type, Optional<BlockPos> origin, int dir, int step, int variant, Optional<BlockPos> source,
		long progress, long nextBuild, List<String> wanted, boolean wallBuilt) {
		static Plan of(VillageData d) { return new Plan(d.planType,d.planOrigin,d.planDir,d.planStep,d.planVariant,d.planSource,d.planProgress,d.nextBuild,List.copyOf(d.wanted),d.wallBuilt); }
		void restore(VillageData d) { d.planType=type;d.planOrigin=origin;d.planDir=dir;d.planStep=step;d.planVariant=variant;d.planSource=source;d.planProgress=progress;d.nextBuild=nextBuild;d.wanted.clear();d.wanted.addAll(wanted);d.wallBuilt=wallBuilt; }
	}

	@SuppressWarnings("unchecked")
	public static int run(ServerLevel level, VillageRef ref) throws Exception {
		int passed = 0, radius = 40, cursor = 10;
		VillageData d = ref.data(); Plan oldPlan = Plan.of(d);
		var profiles = ref.bellEntity().getAttached(Wall.PROFILES);
		var extent = ref.bellEntity().getAttached(VillagerPlus.VILLAGE_EXTENT);
		var buildings = ref.bellEntity().getAttached(VillagerPlus.BUILDINGS);
		var demolition = ref.bellEntity().getAttached(VillagerPlus.DEMOLITION);
		var construction = ref.bellEntity().getAttached(VillagerPlus.CONSTRUCTION);
		var oldLager=d.extra.lager;var oldMore=new ArrayList<>(d.extra.lagerMore);var oldChests=new ArrayList<>(d.extra.lagerChests);
		Container box=ref.chest(level);if(box==null)throw new IllegalStateException("WALL CHECK FAILED: missing fixture chest");
		List<ItemStack> stock=new ArrayList<>();for(int i=0;i<box.getContainerSize();i++)stock.add(box.getItem(i).copy());
		Map<BlockPos,BlockState> world=new LinkedHashMap<>();
		var styleField=Builder.class.getDeclaredField("WALL_STYLES");styleField.setAccessible(true);
		Map<Long,VillageStyle> styles=(Map<Long,VillageStyle>)styleField.get(null);VillageStyle oldStyle=styles.get(ref.bell().asLong());
		VillageStyle style=new VillageStyle(Blocks.COBBLESTONE,Blocks.OAK_PLANKS,Blocks.OAK_PLANKS,Blocks.COBBLESTONE_STAIRS,Blocks.OAK_DOOR,Blocks.OAK_FENCE);
		Villager worker=null;
		try {
			ref.bellEntity().setAttached(Wall.PROFILES,Map.of());
			ref.bellEntity().removeAttached(VillagerPlus.DEMOLITION);ref.bellEntity().removeAttached(VillagerPlus.CONSTRUCTION);
			d.extra.lager=Optional.empty();d.extra.lagerMore.clear();d.extra.lagerChests.clear();styles.put(ref.bell().asLong(),style);
			int[] first=Wall.ringOffset(radius,cursor);
			for(int t=cursor;t<cursor+2;t++) {
				int[] offset=Wall.ringOffset(radius,t);var in=Wall.inward(radius,t);
				for(int kk=0;kk<9;kk++) {
					int x=ref.bell().getX()+offset[0]+in.getStepX()*kk,z=ref.bell().getZ()+offset[1]+in.getStepZ()*kk;
					for(int y=-4;y<=13;y++) {
						BlockPos pos=new BlockPos(x,ref.bell().getY()+y,z);level.getChunkAt(pos);world.putIfAbsent(pos,level.getBlockState(pos));
						level.setBlock(pos,y==-4?Blocks.STONE.defaultBlockState():y<0?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState(),FLAGS);
					}
				}
			}
			var route=Anchor.wallWorkChunks(ref.bell(),ref.bell(),Development.MAX_WALL_RADIUS,0);
			int[] outer=Wall.ringOffset(Development.MAX_WALL_RADIUS,0);
			check(route.contains(new ChunkPos((ref.bell().getX()+outer[0])>>4,(ref.bell().getZ()+outer[1])>>4))
				&&route.size()<=53&&Anchor.WALL_WORK.doesLoad()&&Anchor.WALL_WORK.doesSimulate()&&!Anchor.WALL_WORK.persist()
				&&Anchor.WALL_WORK.timeout()==240L&&BuiltInRegistries.TICKET_TYPE.getKey(Anchor.WALL_WORK).getNamespace().equals("villagerplus"),
				"outer wall work has a bounded registered loading/simulation corridor with expiring tickets");passed++;
			List<Wall.Cell> a=Wall.column(level,ref.bell(),style.wall(),style.stairs(),style.fence(),radius,cursor);
			List<Wall.Cell> b=Wall.column(level,ref.bell(),style.wall(),style.stairs(),style.fence(),radius,cursor+1);
			check(!a.isEmpty()&&a.stream().anyMatch(cell->cell.paid()&&cell.pos().getY()<ref.bell().getY())
				&&!Builder.columnIntact(level,ref.bell(),style,radius,cursor),"water produces paid foundations down to solid bed and an unbuilt column is incomplete");passed++;
			var saved=ref.bellEntity().saveWithFullMetadata(level.registryAccess());
			BlockEntity restored=BlockEntity.loadStatic(ref.bell(),level.getBlockState(ref.bell()),saved,level.registryAccess());
			check(restored!=null&&restored.getAttachedOrElse(Wall.PROFILES,Map.of()).equals(ref.bellEntity().getAttached(Wall.PROFILES)),
				"fixed wall column heights and footings survive block-entity save/load");passed++;
			var in=Wall.inward(radius,cursor);BlockPos stand=ref.bell().offset(first[0]+in.getStepX()*11,0,first[1]+in.getStepZ()*11);
			worker=EntityTypes.VILLAGER.spawn(level,stand,EntitySpawnReason.COMMAND);if(worker==null)throw new IllegalStateException("WALL CHECK FAILED: worker spawn");
			worker.addTag("vp090_fixture");worker.setNoAi(true);worker.setInvulnerable(true);
			box.clearContent();for(int i=0;i<3;i++)box.setItem(i,new ItemStack(Items.COBBLESTONE,64));
			long now=level.getGameTime()+1;d.planType=Blueprints.WALL;d.planOrigin=Optional.of(ref.bell());d.planVariant=radius;d.planStep=cursor;d.planProgress=now;
			Ctx c=new Ctx(level,worker,JobManager.state(worker),worker.getAttachedOrCreate(VillagerPlus.MEMORY),ref,now);
			int cost=(int)(a.stream().filter(Wall.Cell::paid).count()+b.stream().filter(Wall.Cell::paid).count());
			Builder.tick(c,true);
			check(d.planStep==cursor+2&&Builder.columnIntact(level,ref.bell(),style,radius,cursor)&&Builder.columnIntact(level,ref.bell(),style,radius,cursor+1)
				&&JobUtil.count(box,s->s.is(Items.COBBLESTONE))==192-cost,
				"real builder tick finishes two water-supported columns and pays exactly their new wall blocks");passed++;
			check(a.equals(Wall.column(level,ref.bell(),style.wall(),style.stairs(),style.fence(),radius,cursor)),
				"column geometry stays fixed after foundations replace water and raise the heightmap");passed++;
			Wall.Cell changed=a.stream().filter(Wall.Cell::paid).findFirst().orElseThrow();level.setBlock(changed.pos(),Blocks.DIAMOND_BLOCK.defaultBlockState(),FLAGS);
			check(!Builder.columnIntact(level,ref.bell(),style,radius,cursor),"foreign solid blocks cannot masquerade as completed wall material");passed++;
			int remaining=JobUtil.count(box,s->s.is(Items.COBBLESTONE));d.planStep=cursor;d.planProgress=now+100;
			Builder.tick(new Ctx(level,worker,c.st(),c.mem(),ref,now+100),true);
			check(d.planStep==cursor&&level.getBlockState(changed.pos()).is(Blocks.DIAMOND_BLOCK)&&JobUtil.count(box,s->s.is(Items.COBBLESTONE))==remaining,
				"a blocked column preserves foreign property, the cursor and the material stock");passed++;
			level.setBlock(changed.pos(),Blocks.AIR.defaultBlockState(),FLAGS);
			check(!Builder.columnIntact(level,ref.bell(),style,radius,cursor)&&!Builder.columnIntact(level,ref.bell(),style,0,0),
				"missing cells and unavailable empty column plans remain incomplete");passed++;
			ref.bellEntity().setAttached(VillagerPlus.VILLAGE_EXTENT,new VillageExtent(ref.bell().getX()-80,ref.bell().getY(),ref.bell().getZ()-70,ref.bell().getX()+70,ref.bell().getY()+12,ref.bell().getZ()+80));
			ref.bellEntity().setAttached(VillagerPlus.BUILDINGS,List.of(
				new Built(Blueprints.HOUSE,ref.bell().east(110),0,64<<2,List.of()),
				new Built(Blueprints.HOUSE,ref.bell().west(110),0,64<<2,List.of()),
				new Built(Blueprints.HOUSE,ref.bell().north(110),0,64<<2,List.of()),
				new Built(Blueprints.HOUSE,ref.bell().south(110),0,64<<2,List.of())));
			var bounds=Development.bounds(level,ref);int needed=Development.neededWallRadius(level,ref);
			int edge=Math.max(Math.max(Math.abs(bounds.minX()-ref.bell().getX()),Math.abs(bounds.maxX()-ref.bell().getX())),
				Math.max(Math.abs(bounds.minZ()-ref.bell().getZ()),Math.abs(bounds.maxZ()-ref.bell().getZ())));
			boolean ringOutside=true;
			for(int t=0;t<8*needed;t++){
				int[] offset=Wall.ringOffset(needed,t);int x=ref.bell().getX()+offset[0],z=ref.bell().getZ()+offset[1];
				if(x>=bounds.minX()&&x<=bounds.maxX()&&z>=bounds.minZ()&&z<=bounds.maxZ()){ringOutside=false;break;}
			}
			check(bounds.minX()<ref.bell().getX()-110&&bounds.maxX()>ref.bell().getX()+110
				&&bounds.minZ()<ref.bell().getZ()-110&&bounds.maxZ()>ref.bell().getZ()+110
				&&needed-edge>=14&&needed<=Development.MAX_WALL_RADIUS&&ringOutside,
				"wall radius contains remembered original district and all four outer additions with a clear ring");passed++;
			return passed;
		} finally {
			if(worker!=null){worker.discard();de.villagerplus.village.Villages.forget(worker.getUUID());}
			world.forEach((pos,state)->level.setBlock(pos,state,FLAGS));
			box.clearContent();for(int i=0;i<stock.size();i++)box.setItem(i,stock.get(i));
			oldPlan.restore(d);d.extra.lager=oldLager;d.extra.lagerMore.clear();d.extra.lagerMore.addAll(oldMore);d.extra.lagerChests.clear();d.extra.lagerChests.addAll(oldChests);
			if(oldStyle==null)styles.remove(ref.bell().asLong());else styles.put(ref.bell().asLong(),oldStyle);
			if(profiles==null)ref.bellEntity().removeAttached(Wall.PROFILES);else ref.bellEntity().setAttached(Wall.PROFILES,profiles);
			if(extent==null)ref.bellEntity().removeAttached(VillagerPlus.VILLAGE_EXTENT);else ref.bellEntity().setAttached(VillagerPlus.VILLAGE_EXTENT,extent);
			if(buildings==null)ref.bellEntity().removeAttached(VillagerPlus.BUILDINGS);else ref.bellEntity().setAttached(VillagerPlus.BUILDINGS,buildings);
			if(demolition==null)ref.bellEntity().removeAttached(VillagerPlus.DEMOLITION);else ref.bellEntity().setAttached(VillagerPlus.DEMOLITION,demolition);
			if(construction==null)ref.bellEntity().removeAttached(VillagerPlus.CONSTRUCTION);else ref.bellEntity().setAttached(VillagerPlus.CONSTRUCTION,construction);
			ref.dirty();
		}
	}
	private static void check(boolean ok,String label){if(!ok)throw new IllegalStateException("WALL CHECK FAILED: "+label);System.out.println("WALL PASS "+label);}
}
