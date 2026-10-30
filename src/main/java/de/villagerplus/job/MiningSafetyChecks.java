package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.village.VillageData;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Isolated world checks: collision-safe steps and deferred mine closure, with fixture cleanup. */
public final class MiningSafetyChecks {
    private MiningSafetyChecks() { }
    public static int run(ServerLevel level, BlockPos e) {
        Map<BlockPos, BlockState> before = new HashMap<>();
        Villager v = null;
        int checks = 0;
        try {
            for (int x = -8; x <= 12; x++) for (int z = -9; z <= 9; z++) {
                level.getChunkAt(e.offset(x, 0, z));
                for (int y = -5; y <= 4; y++) {
                    BlockPos p = e.offset(x,y,z);
                    if (level.getBlockEntity(p) != null) throw new IllegalStateException("Mine fixture intersects a block entity");
                    before.put(p,level.getBlockState(p));
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                }
            }
            for (int i=0;i<4;i++) level.setBlock(e.offset(i,-i-1,0),Blocks.STONE.defaultBlockState(),2);
            BlockPos cell=e.offset(2,-2,0);
            v=EntityTypes.VILLAGER.create(level,EntitySpawnReason.COMMAND);
            if(v==null) throw new IllegalStateException("Cannot create mine fixture");
            v.setPos(e.getX()-5.5,e.getY(),e.getZ()+.5);v.setNoAi(true);v.setInvulnerable(true);v.addTag("vp090_fixture");
            level.addFreshEntity(v);
            Ctx c=new Ctx(level,v,new JobState(),v.getAttachedOrCreate(VillagerPlus.MEMORY),null,level.getGameTime());
            check(Mining.safeCell(c,cell),"clear supported cell accepts villager");checks++;
            var oldPos=v.position();
            level.setBlock(cell.above(),Blocks.STONE.defaultBlockState(),2);
            check(!Mining.teleport(c,cell)&&v.position().equals(oldPos),"blocked headroom rejects movement without moving villager");checks++;
            level.setBlock(cell.above(),Blocks.AIR.defaultBlockState(),2);
            level.setBlock(cell.below(),Blocks.AIR.defaultBlockState(),2);
            check(!Mining.safeCell(c,cell),"missing floor rejects cell");checks++;
            level.setBlock(cell.below(),Blocks.STONE.defaultBlockState(),2);
            level.setBlock(cell,Blocks.WATER.defaultBlockState(),2);
            check(!Mining.safeCell(c,cell),"water rejects cell");checks++;
            level.setBlock(cell,Blocks.AIR.defaultBlockState(),2);
            check(Mining.teleport(c,cell)&&v.blockPosition().equals(cell),"free cell accepts exact step");checks++;
            var m=new VillageData.Mine(e,Direction.EAST.get2DDataValue(),3,e.getY()-5,true);
            check(!Mining.canSeal(level,m),"miner in descending shaft prevents sealing");checks++;
            Mining.barricade(level,m,false);
            check(level.getBlockState(e).isAir()&&level.getBlockState(cell).isAir(),"occupied mine keeps entrance and shaft open");checks++;
            v.teleportTo(e.getX()-6.5,e.getY(),e.getZ()+.5);
            check(Mining.canSeal(level,m),"empty loaded shaft can close");checks++;
            VillageData data=new VillageData();data.mine=Optional.of(m);
            Mining.tidyOldMines(level,data);
            check(level.getBlockState(e).is(Blocks.SPRUCE_PLANKS)&&level.getBlockState(cell).isAir(),"retry barricades shallow mine without backfilling shaft");checks++;
            var horizontal=new VillageData.Mine(e,Direction.EAST.get2DDataValue(),6,e.getY(),true);
            v.teleportTo(e.getX()+5.5,e.getY(),e.getZ()-4.5);
            check(!Mining.canSeal(level,horizontal),"occupant in side branch prevents sealing");checks++;
            var remote=new VillageData.Mine(e.east(20000),Direction.EAST.get2DDataValue(),3,e.getY()-5,true);
            check(!Mining.canSeal(level,remote),"unloaded mine cannot be assumed empty");checks++;
            return checks;
        } finally {
            if(v!=null)v.discard();
            before.forEach((p,state)->level.setBlock(p,state,2));
        }
    }
    private static void check(boolean ok,String message) {
        if(!ok)throw new IllegalStateException("MINING SAFETY CHECK FAILED: "+message);
    }
}
