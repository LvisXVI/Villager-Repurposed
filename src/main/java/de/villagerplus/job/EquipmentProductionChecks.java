package de.villagerplus.job;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.Specials;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;

/** Arrival is simulated explicitly; inventory transactions and loaded recipes execute on a real server. */
public final class EquipmentProductionChecks {
 private EquipmentProductionChecks() { }
 private static void check(boolean value,String label) {if(!value)throw new IllegalStateException("EQUIPMENT PRODUCTION: "+label);}
 public static int run(ServerLevel level,BlockPos at) {
  var ref=Checks090.fixtureForEquipment(level,at);
  BlockPos shed=at.east(20),barrel=shed.east(),bench=shed.east(3);
  level.getChunkAt(bench);level.setBlock(barrel,Blocks.BARREL.defaultBlockState(),3);
  Specials.set(ref,Blueprints.TOOLSHED,shed);
  var depot=(BarrelBlockEntity)level.getBlockEntity(barrel);
  var source=ref.chest(level);source.clearContent();
  source.setItem(0,new ItemStack(Items.IRON_INGOT,3));source.setItem(1,new ItemStack(Items.STICK,2));
  depot.setItem(0,new ItemStack(Items.DIAMOND_AXE));depot.setItem(1,new ItemStack(Items.DIAMOND_SHOVEL));
  depot.setItem(2,new ItemStack(Items.DIAMOND_HOE));depot.setItem(3,new ItemStack(Items.DIAMOND_SWORD));
  var v=EntityTypes.VILLAGER.spawn(level,bench.south(),EntitySpawnReason.COMMAND);
  v.addTag("vp090_fixture");v.setNoAi(true);v.setInvulnerable(true);
  var state=new JobState();var memory=v.getAttachedOrCreate(VillagerPlus.MEMORY);
  long now=level.getGameTime()+1000;int checks=0;
  try {
   Ctx c=new Ctx(level,v,state,memory,ref,now);
   check(!EquipmentProduction.work(c)&&source.getItem(0).getCount()==3,"no station leaves source unchanged");checks++;
   level.setBlock(bench,Blocks.CRAFTING_TABLE.defaultBlockState(),3);state.toolCraftScanAt=0;
   check(EquipmentProduction.work(c)&&source.getItem(0).getCount()==3&&v.getInventory().isEmpty(),"distant worker must fetch ingredients before crafting");checks++;
   v.teleportTo(at.getX()+2.5,at.getY(),at.getZ()+.5);
   EquipmentProduction.work(c);
   check(JobUtil.count(v.getInventory(),s->s.is(Items.IRON_INGOT))==3&&source.getItem(0).isEmpty(),"physical pickup transfers exactly three ingots");checks++;
   EquipmentProduction.work(c);
   check(JobUtil.count(v.getInventory(),s->s.is(Items.STICK))==2&&source.getItem(1).isEmpty(),"second pickup transfers exactly two sticks");checks++;
   EquipmentProduction.work(c);
   check(JobUtil.count(v.getInventory(),s->s.is(Items.IRON_PICKAXE))==0&&JobUtil.count(v.getInventory(),s->s.is(Items.IRON_INGOT))==3,"walking to bench does not consume ingredients remotely");checks++;
   v.teleportTo(bench.getX()+.5,bench.getY(),bench.getZ()+2.5);
   EquipmentProduction.work(c);
   check(JobUtil.count(v.getInventory(),s->s.is(Items.IRON_PICKAXE))==1&&JobUtil.count(v.getInventory(),s->s.is(Items.IRON_INGOT)||s.is(Items.STICK))==0,"loaded pickaxe recipe consumes three ingots and two sticks at bench");checks++;
   for(int slot=4;slot<depot.getContainerSize();slot++)depot.setItem(slot,new ItemStack(Items.DIAMOND_AXE));
   check(!EquipmentProduction.work(c)&&JobUtil.count(v.getInventory(),s->s.is(Items.IRON_PICKAXE))==1,"full depot preserves carried replacement");checks++;
   depot.setItem(4,ItemStack.EMPTY);depot.setAttached(VillagerPlus.OWNER,v.getUUID());
   check(!EquipmentProduction.work(c)&&JobUtil.count(v.getInventory(),s->s.is(Items.IRON_PICKAXE))==1,"private depot cannot receive communal tool");checks++;
   depot.removeAttached(VillagerPlus.OWNER);
   v.teleportTo(barrel.getX()+.5,barrel.getY(),barrel.getZ()+2.5);
   EquipmentProduction.work(c);
   check(JobUtil.count(v.getInventory(),s->s.is(Items.IRON_PICKAXE))==0&&JobUtil.count(depot,s->s.is(Items.IRON_PICKAXE))==1,"finished replacement transfers to toolshed only on arrival");checks++;
   depot.setItem(4,ItemStack.EMPTY);source.setItem(0,new ItemStack(Items.IRON_INGOT,3));source.setItem(1,new ItemStack(Items.STICK,2));
   ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES,Map.of("minecraft:iron_pickaxe",0));
   check(!EquipmentProduction.work(c)&&source.getItem(0).getCount()==3&&source.getItem(1).getCount()==2,"zero stock target suppresses recipe before pickup");checks++;
   ref.bellEntity().removeAttached(VillagerPlus.STOCK_POLICIES);
   level.setBlock(shed.south(3),Blocks.ANVIL.defaultBlockState(),3);
   state.idleUntil[1]=0;
   EquipmentMaintenance.repairAtToolshed(c);
   check(state.idleUntil[1]==0,"empty repair queue does not idle other smith work");checks++;
   return checks;
  } finally {
   v.discard();Specials.remove(ref,Blueprints.TOOLSHED);
   for(BlockPos pos:new BlockPos[]{at,at.below(),at.east(3),at.east(3).below(),barrel,bench,shed.south(3)})level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
  }
 }
}
