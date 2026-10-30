from pathlib import Path
r=Path('src/main/java/de/villagerplus')
p=r/'job/JobState.java';s=p.read_text(encoding='utf-8').replace('public class JobState {','public class JobState {\n public long nextHousing;');p.write_text(s,encoding='utf-8')
p=r/'job/JobManager.java';s=p.read_text(encoding='utf-8').replace('if ((now + v.getId()) % Config.HOUSING_INTERVAL == 0) {','JobState scheduled = state(v);\n\t\tif (now >= scheduled.nextHousing) {\n\t\t\tscheduled.nextHousing = now + Config.HOUSING_INTERVAL;');p.write_text(s,encoding='utf-8')
p=r/'village/Plaza.java';s=p.read_text(encoding='utf-8').replace('now+2400','now+600').replace('for(int i=0;i<48;i++)','for(int i=0;i<128;i++)').replace('hint.getRandom(),16,36','hint.getRandom(),16,64').replace('high-low>2','high-low>4');p.write_text(s,encoding='utf-8')
p=r/'village/Market.java';s=p.read_text(encoding='utf-8').replace('JobUtil.approach(c,st.pos().above(),4)','JobUtil.approach(c,counter(s),2.5)');p.write_text(s,encoding='utf-8')
p=r/'village/Roads.java';s=p.read_text(encoding='utf-8').replace('s.is(BlockTags.DIRT)||s.is(Blocks.SAND)', 's.is(BlockTags.DIRT)||s.is(Blocks.GRASS_BLOCK)||s.is(Blocks.SAND)');p.write_text(s,encoding='utf-8')
p=r/'dev/Checks090.java';s=p.read_text(encoding='utf-8').replace('seller.teleportTo(st.pos().getX()+.5,st.pos().getY()+2,st.pos().getZ()+.5);','seller.teleportTo(current.origin().getX()-2.5,current.origin().getY(),current.origin().getZ()+.5);');s=s.replace('  if(mode.equals("board")) {','''  if(mode.equals("village")) {
   var ref=Villages.villageNear(level,player.blockPosition(),128);if(ref==null)return 0;
   source.sendSuccess(()->Component.literal("Bell "+ref.bell()+" chest "+ref.data().chest+" plaza "+Market.of(ref).plaza()),false);return 1;
  }
  if(mode.equals("board")) {''');p.write_text(s,encoding='utf-8')
