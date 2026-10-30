from pathlib import Path
r=Path('src/main/java/de/villagerplus')
p=r/'village/Market.java';s=p.read_text(encoding='utf-8').replace('.contains(de.villagerplus.Compat.jobKey(v.getVillagerData().profession()))','.contains(String.valueOf(de.villagerplus.Compat.jobKey(v.getVillagerData().profession())))');p.write_text(s,encoding='utf-8')
p=r/'Config.java';s=p.read_text(encoding='utf-8').replace('VILLAGE_RADIUS = 48','VILLAGE_RADIUS = 96');p.write_text(s,encoding='utf-8')
for name in ['village/Market.java','village/VillageStats.java','village/VillagerInfo.java','net/BoardData.java','village/Network.java','village/Villages.java']:
 p=r/name;s=p.read_text(encoding='utf-8').replace('new AABB(ref.bell()).inflate(48)','new AABB(ref.bell()).inflate(de.villagerplus.Config.VILLAGE_RADIUS)').replace('new AABB(ref.bell()).inflate(64)','new AABB(ref.bell()).inflate(de.villagerplus.Config.VILLAGE_RADIUS)').replace('new AABB(other.bell()).inflate(48)','new AABB(other.bell()).inflate(de.villagerplus.Config.VILLAGE_RADIUS)').replace('new net.minecraft.world.phys.AABB(vr.bell()).inflate(48)','new net.minecraft.world.phys.AABB(vr.bell()).inflate(de.villagerplus.Config.VILLAGE_RADIUS)').replace('new net.minecraft.world.phys.AABB(ref.bell()).inflate(64)','new net.minecraft.world.phys.AABB(ref.bell()).inflate(de.villagerplus.Config.VILLAGE_RADIUS)').replace('72.0 * 72.0','de.villagerplus.Config.VILLAGE_RADIUS * (double)de.villagerplus.Config.VILLAGE_RADIUS');p.write_text(s,encoding='utf-8')
p=r/'job/JobManager.java';s=p.read_text(encoding='utf-8').replace('net.minecraft.core.BlockPos home = ref.chestPos(c.level()) != null ? ref.chestPos(c.level()) : ref.bell();','net.minecraft.core.BlockPos home = c.mem().homeBed.orElse(ref.bell());').replace('> 70.0 * 70.0','> Config.VILLAGE_RADIUS * (double) Config.VILLAGE_RADIUS');p.write_text(s,encoding='utf-8')
p=r/'build/Builder.java';s=p.read_text(encoding='utf-8').replace('boolean grew = pop > d.popAtLastHouse;', 'if (d.popAtLastHouse == 0) { d.popAtLastHouse = pop; ref.dirty(); }\n\t\t\tboolean grew = pop > d.popAtLastHouse;').replace('(homeless > 0 || (homes <= pop && grew))','(grew && (homeless > 0 || homes <= pop))');p.write_text(s,encoding='utf-8')
p=r/'village/Plaza.java';s=p.read_text(encoding='utf-8').replace('  BlockPos old=ref.chestPos(level), chest=', '''  for(Direction dir:Direction.Plane.HORIZONTAL) for(int n=8;n<=12;n++) {
   BlockPos road=c.relative(dir,n);int height=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,road.getX(),road.getZ());
   int target=c.getY()-(n-7);if(height>target+1)break;
   for(int side=-1;side<=1;side++) {
    BlockPos stair=new BlockPos(road.getX(),target,road.getZ()).relative(dir.getClockWise(),side);
    var cur=level.getBlockState(stair);if(!cur.isAir()&&!cur.canBeReplaced()&&!VillageStyle.natural(cur))continue;
    var stairState=p.roofStair().defaultBlockState();if(p.roofStair() instanceof StairBlock)stairState=stairState.setValue(StairBlock.FACING,dir.getOpposite());
    level.setBlock(stair,stairState,3);
   }
  }
  BlockPos old=ref.chestPos(level), chest=''');p.write_text(s,encoding='utf-8')
p=r/'client/BoardScreen.java';s=p.read_text(encoding='utf-8').replace('tab = idx;\n\t\t\t\tscroll', 'if (idx == 5 && selected >= 0) { person = selected; if (filterPerson != null) filterPerson.setMessage(Component.literal("Bewohner: "+data.residents().get(person).split("\\\\|")[0])); }\n\t\t\t\ttab = idx;\n\t\t\t\tscroll');p.write_text(s,encoding='utf-8')
