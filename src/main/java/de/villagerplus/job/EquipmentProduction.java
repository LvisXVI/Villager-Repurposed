package de.villagerplus.job;

import de.villagerplus.build.Blueprints;
import de.villagerplus.village.Lager;
import de.villagerplus.village.Specials;
import de.villagerplus.village.StockPolicy;
import de.villagerplus.village.Villages;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/** Loaded recipes, physical ingredient pickup, workbench crafting and toolshed delivery. */
final class EquipmentProduction {
    private static final List<TagKey<Item>> KINDS = List.of(ItemTags.PICKAXES,ItemTags.AXES,ItemTags.SHOVELS,ItemTags.HOES,ItemTags.SWORDS);
    private EquipmentProduction() { }

    static boolean work(Ctx c) {
        if(c.village()==null || !Specials.has(c.village(),Blueprints.TOOLSHED)) return false;
        Container inv=c.v().getInventory();
        ItemStack carried=firstEquipment(inv);
        if(!carried.isEmpty()) {
            if(Specials.depotWithRoom(c.level(),c.village(),Blueprints.TOOLSHED,carried)==null) return false;
            Anim.hold(c,carried.getItem());
            return JobUtil.depositSurplus(c,EquipmentProduction::equipment,1,0);
        }
        BlockPos bench=bench(c);
        if(bench==null)return false;
        List<Container> depots=Specials.containers(c.level(),c.village(),Blueprints.TOOLSHED);
        if(depots.isEmpty())return false;
        int reserve=Math.max(1,Math.min(8,(Villages.residents(c.level(),c.village(),96,v->!v.isBaby()).size()+7)/8));
        List<TagKey<Item>> needed=KINDS.stream().filter(kind->usable(depots,kind)<reserve).toList();
        if(needed.isEmpty())return false;
        List<BlockPos> sources=Lager.materialChests(c.level(),c.village());
        Container available=inv;
        for(BlockPos pos:sources) {
            Container source=HopperBlockEntity.getContainerAt(c.level(),pos);
            if(source!=null)available=new CompoundContainer(available,source);
        }
        List<CraftingRecipe> recipes=new ArrayList<>();
        for(var holder:c.level().recipeAccess().getRecipes()) {
            if(holder.value() instanceof CraftingRecipe recipe) {
                ItemStack preview=RecipeCrafting.preview(recipe);
                if(preview==null || needed.stream().anyMatch(preview::is))recipes.add(recipe);
            }
        }
        recipes.sort(Comparator.comparingInt(EquipmentProduction::preference));
        for(CraftingRecipe recipe:recipes) {
            CraftingInput input=RecipeCrafting.inputFor(recipe,available,c.level());
            if(input==null)continue;
            ItemStack output=recipe.assemble(input);
            if(output.isEmpty() || needed.stream().noneMatch(output::is)
                || output.getCount()>StockPolicy.deliveryRoom(c.level(),c.village(),output.getItem())
                || Specials.depotWithRoom(c.level(),c.village(),Blueprints.TOOLSHED,output)==null)continue;
            // Each pickup is re-planned from real inventories. Interruptions/reloads leave all carried items intact.
            for(ItemStack ingredient:input.items()) {
                if(ingredient.isEmpty())continue;
                int required=0;
                for(ItemStack part:input.items())if(ItemStack.isSameItemSameComponents(part,ingredient))required+=part.getCount();
                int own=JobUtil.count(inv,s->ItemStack.isSameItemSameComponents(s,ingredient));
                if(own>=required)continue;
                for(BlockPos pos:sources) {
                    Container source=HopperBlockEntity.getContainerAt(c.level(),pos);
                    if(source==null)continue;
                    for(int slot=0;slot<source.getContainerSize();slot++) {
                        ItemStack stack=source.getItem(slot);
                        if(stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack,ingredient))continue;
                        c.setActivity("holt Zutaten für Ersatzwerkzeuge");Anim.hold(c,stack.getItem());
                        if(!JobUtil.approachLong(c,pos,3.0))return true;
                        ItemStack moving=stack.copyWithCount(Math.min(required-own,stack.getCount()));
                        int before=moving.getCount();
                        ItemStack rest=HopperBlockEntity.addItem(null,inv,moving,null);
                        int moved=before-rest.getCount();
                        if(moved==0)return false;
                        stack.shrink(moved);source.setChanged();inv.setChanged();
                        JobUtil.swing(c.v());c.st().cooldownUntil=c.now()+40;return true;
                    }
                }
                return false;
            }
            c.setActivity("fertigt Ersatzwerkzeug an der Werkbank");
            if(!JobUtil.approachLong(c,bench,2.8))return true;
            return RecipeCrafting.craft(c,inv,null,s->ItemStack.isSameItemSameComponents(s,output),output.getCount());
        }
        return false;
    }

    private static int preference(CraftingRecipe recipe) {
        ItemStack preview=RecipeCrafting.preview(recipe);
        if(preview==null||preview.isEmpty())return 3;
        String path=BuiltInRegistries.ITEM.getKey(preview.getItem()).getPath();
        return path.startsWith("iron_")?0:path.startsWith("stone_")?1:2;
    }
    private static boolean equipment(ItemStack stack) {
        return !stack.isEmpty() && StockPolicy.category(stack.getItem())==StockPolicy.Category.TOOLS;
    }
    private static ItemStack firstEquipment(Container inv) {
        for(int i=0;i<inv.getContainerSize();i++)if(equipment(inv.getItem(i)))return inv.getItem(i);
        return ItemStack.EMPTY;
    }
    private static int usable(List<Container> boxes,TagKey<Item> kind) {
        int count=0;
        for(Container box:boxes)for(int i=0;i<box.getContainerSize();i++) {
            ItemStack item=box.getItem(i);
            if(!item.isEmpty()&&item.is(kind)&&(!item.isDamageableItem()||item.getDamageValue()<item.getMaxDamage()*3/4))count+=item.getCount();
        }
        return count;
    }
    private static BlockPos bench(Ctx c) {
        BlockPos origin=Specials.get(c.village(),Blueprints.TOOLSHED);
        BlockPos cached=c.st().toolCraftBench;
        if(cached!=null && Math.abs(cached.getX()-origin.getX())<=8 && Math.abs(cached.getZ()-origin.getZ())<=8
            && c.level().hasChunkAt(cached)&&c.level().getBlockState(cached).is(Blocks.CRAFTING_TABLE))return cached;
        if(c.now()<c.st().toolCraftScanAt)return null;
        c.st().toolCraftScanAt=c.now()+200;c.st().toolCraftBench=null;
        for(BlockPos pos:BlockPos.betweenClosed(origin.offset(-8,-2,-8),origin.offset(8,6,8))) {
            if(c.level().hasChunkAt(pos)&&c.level().getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                c.st().toolCraftBench=pos.immutable();return c.st().toolCraftBench;
            }
        }
        return null;
    }
}
