package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.village.Lager;
import de.villagerplus.village.Roads;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/** Physical maintenance of completed road sections; carried stock survives interruption/reload. */
public final class RoadBuilderJob implements Job {
    public java.util.function.Predicate<ItemStack> produce() {return s->s.is(net.minecraft.tags.ItemTags.LOGS)||s.is(net.minecraft.tags.ItemTags.SAPLINGS)||s.is(net.minecraft.world.item.Items.STICK);}
    public boolean primary(Ctx c) { return work(c) || Workshop.tick(c); }
    public boolean secondary(Ctx c) { return work(c); }

    public static boolean work(Ctx c) {
        return c.st().roadBuilding?construct(c):maintain(c)||construct(c);
    }

    public static boolean construct(Ctx c) {
        if(c.village()==null)return false;
        var st=c.st();
        if(st.roadRepair==null) {
            if(c.now()<st.roadBuildScanAt)return false;
            st.roadBuildScanAt=c.now()+200;
            st.roadRepair=Roads.nextConstruction(c.level(),c.village());
            st.roadBuilding=st.roadRepair!=null;
        }
        return st.roadBuilding&&perform(c);
    }

    public static boolean maintain(Ctx c) {
        if(c.village()==null)return false;
        var st=c.st();
        if(st.roadBuilding)return false;
        if(st.roadRepair==null) {
            if(c.now()<st.roadScanAt)return false;
            st.roadScanAt=c.now()+200;
            st.roadRepair=Roads.nextRepair(c.level(),c.village());
        }
        return perform(c);
    }

    private static boolean perform(Ctx c) {
        var st=c.st();Roads.Repair task=st.roadRepair;if(task==null){st.roadBuilding=false;return false;}
        if(!Roads.repairable(c.level(),task)) { Anim.cancelBreaking(c.v());st.roadRepair=null;st.roadBuilding=false;return false; }
        if(task.removal()!=null) {
            c.setActivity("rodet den Straßenkorridor");
            if(!JobUtil.approachLong(c,task.worksite(),3)) {
                if(st.cooldownUntil>c.now()){Anim.cancelBreaking(c.v());st.roadRepair=null;st.roadBuilding=false;}
                return true;
            }
            if(!Anim.breakStep(c,task.ground(),task.removal(),net.minecraft.world.item.Items.IRON_AXE))return true;
            var drops=net.minecraft.world.level.block.Block.getDrops(task.removal(),c.level(),task.ground(),null,c.v(),new ItemStack(net.minecraft.world.item.Items.IRON_AXE));
            if(c.level().setBlock(task.ground(),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),3)) {
                for(ItemStack drop:drops)JobUtil.give(c.level(),c.v(),drop);
                Anim.blockBreak(c.level(),task.ground(),task.removal());c.mem().jobsDone++;
            }
            st.roadRepair=null;st.roadBuilding=false;st.roadBuildScanAt=0;return true;
        }
        var item=task.paving().getBlock().asItem();
        if(JobUtil.count(c.v().getInventory(),s->s.is(item))==0) {
            for(BlockPos p:Lager.materialChests(c.level(),c.village())) {
                // Also protect the private half of a double chest.
                boolean privateNeighbor=false;
                for(var d:net.minecraft.core.Direction.Plane.HORIZONTAL) {
                    var be=c.level().getBlockEntity(p.relative(d));
                    if(be instanceof net.minecraft.world.level.block.entity.ChestBlockEntity&&be.getAttached(VillagerPlus.OWNER)!=null)privateNeighbor=true;
                }
                if(privateNeighbor)continue;
                var box=HopperBlockEntity.getContainerAt(c.level(),p);
                if(box==null||JobUtil.count(box,s->s.is(item))==0)continue;
                c.setActivity("holt Straßenbaumaterial");
                if(!JobUtil.approachLong(c,p,3)) {
                    if(st.cooldownUntil>c.now())st.roadRepair=null;
                    return true;
                }
                for(int slot=0;slot<box.getContainerSize();slot++) {
                    ItemStack sample=box.getItem(slot);
                    if(!sample.is(item))continue;
                    ItemStack taken=sample.copyWithCount(1);
                    if(!c.v().getInventory().canAddItem(taken))return false;
                    sample.shrink(1);
                    if(sample.isEmpty())box.setItem(slot,ItemStack.EMPTY);
                    c.v().getInventory().addItem(taken);box.setChanged();break;
                }
                return true;
            }
            st.roadRepair=null;st.roadBuilding=false;return false;
        }
        c.setActivity(task.completionKey()!=null?"setzt Straßenbeleuchtung":st.roadBuilding?"baut die Überlandstraße":"bessert die Überlandstraße aus");
        if(!JobUtil.approachLong(c,task.worksite(),3)) {
            if(st.cooldownUntil>c.now())st.roadRepair=null;
            return true;
        }
        boolean done=repair(c,task);
        if(done&&task.completionKey()!=null&&c.village()!=null) {
            var roads=new java.util.HashMap<>(c.village().bellEntity().getAttachedOrElse(VillagerPlus.ROADS,java.util.Map.of()));
            roads.put(task.completionKey(),1L);
            c.village().bellEntity().setAttached(VillagerPlus.ROADS,java.util.Map.copyOf(roads));c.village().dirty();
        }
        st.roadRepair=null;st.roadBuilding=false;
        if(done)st.roadBuildScanAt=0;
        return done;
    }

    static boolean repair(Ctx c,Roads.Repair task) {
        if(c.v().position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(task.worksite()))>9
            ||!Roads.repairable(c.level(),task))return false;
        if(!c.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new net.minecraft.world.phys.AABB(task.ground()),e->e.isAlive()).isEmpty())return false;
        var item=task.paving().getBlock().asItem();
        if(JobUtil.count(c.v().getInventory(),s->s.is(item))<1)return false;
        if(!c.level().setBlock(task.ground(),task.paving(),3))return false;
        JobUtil.take(c.v().getInventory(),s->s.is(item),1);
        Anim.hold(c,item);JobUtil.swing(c.v());
        c.level().playSound(null,task.ground(),task.paving().getSoundType().getPlaceSound(),SoundSource.BLOCKS,1,1);
        c.mem().jobsDone++;
        return true;
    }
}
