from pathlib import Path
import json
r=Path('src/main/java/de/villagerplus')
(r/'mixin/VillagerNameplateMixin.java').write_text('''package de.villagerplus.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import de.villagerplus.village.Names;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Compact two-line village nameplate, depth-tested and limited to 24 blocks. */
@Mixin(EntityRenderer.class)
public abstract class VillagerNameplateMixin {
 @Shadow public abstract Font getFont();
 @Inject(method="extractRenderState",at=@At("RETURN"))
 private void vp$plate(Entity entity,EntityRenderState state,float partial,CallbackInfo ci) {
  if(!(entity instanceof Villager v) || !v.hasCustomName() || v.isInvisible() || state.distanceToCameraSq>576)return;
  state.nameTag=Component.literal("  "+v.getCustomName().getString()+"  ").withColor(0xFFE8AF);
  state.scoreText=Component.literal((v.isBaby()?"Lehrling":Names.profession(v.getVillagerData().profession()))+" · "+"★".repeat(Math.max(1,Math.min(5,v.getVillagerData().level()))))
   .withColor(v.getHealth()<v.getMaxHealth()/2?0xF1A58A:0xBBD4B0);
  if(state.nameTagAttachment==null)state.nameTagAttachment=new net.minecraft.world.phys.Vec3(0,v.getBbHeight()+.5,0);
 }
 @Inject(method="submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",at=@At("HEAD"),cancellable=true)
 private void vp$draw(EntityRenderState state,PoseStack pose,SubmitNodeCollector out,CameraRenderState camera,CallbackInfo ci) {
  if(state.entityType!=EntityTypes.VILLAGER || state.nameTag==null || state.scoreText==null || state.distanceToCameraSq>576 || state.nameTagAttachment==null)return;
  pose.pushPose();var at=state.nameTagAttachment;pose.translate(at.x,at.y+.15,at.z);pose.mulPose(camera.orientation);pose.scale(.0225F,-.0225F,.0225F);
  Font font=getFont();
  out.submitText(pose,-font.width(state.nameTag)/2F,-12,state.nameTag.getVisualOrderText(),false,Font.DisplayMode.NORMAL,state.lightCoords,0xFFFFE8AF,0xDE30271D,0);
  out.submitText(pose,-font.width(state.scoreText)/2F,0,state.scoreText.getVisualOrderText(),false,Font.DisplayMode.NORMAL,state.lightCoords,0xFFBBD4B0,0xDE30271D,0);
  pose.popPose();ci.cancel();
 }
}
''',encoding='utf-8')
p=Path('src/main/resources/villagerplus.mixins.json');d=json.loads(p.read_text());d['client']=['VillagerNameplateMixin'];p.write_text(json.dumps(d,indent=2)+'\n')
p=r/'dev/Checks090.java';s=p.read_text(encoding='utf-8');s=s.replace('var be=l.getBlockEntity(at); var data=', 'var be=l.getBlockEntity(at);be.setAttached(VillagerPlus.NETWORK,TradeNetwork.create(at));be.setAttached(VillagerPlus.MARKET,MarketData.empty()); var data=');s=s.replace('  if(mode.equals("verify")){','''  if(mode.equals("board")) {
   var ref=Villages.villageNear(level,player.blockPosition(),96);if(ref==null)return 0;
   de.villagerplus.block.DorftafelBlock.open(level,player,ref.data().extra.board.orElse(ref.bell()));return 1;
  }
  if(mode.equals("trader")) {
   var traders=level.getEntitiesOfClass(net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader.class,new AABB(player.blockPosition()).inflate(120));if(traders.isEmpty())return 0;
   var trader=traders.get(0);player.teleportTo(trader.getX()+2,trader.getY(),trader.getZ());trader.interact(player,InteractionHand.MAIN_HAND);return 1;
  }
  if(mode.equals("verify")){''');s=s.replace('  passed=0;', '  passed=0;\n  for(Entity e:level.getEntitiesOfClass(Entity.class,new AABB(A).inflate(130),x->x.entityTags().contains("vp090_fixture")))e.discard();');p.write_text(s,encoding='utf-8')
p=r/'village/Market.java';s=p.read_text(encoding='utf-8').replace('" für 1 Smaragd",64);','" für 1 Smaragd",0);');
# A small pitched roof, beams, window and striped awning distinguish the shops from plain boxes.
s=s.replace('  out.add(Step.block(stockPos(s),Blocks.CHEST));','''  for(int x=-3;x<=3;x++) for(int z=-3;z<=2;z++) {
   int y=3+3-Math.abs(x);Block roof=Math.abs(x)==0?p.roofFull():p.roofStair();
   final int side=x;
   out.add(Step.block(at(s,x,z,y),roof,b->{var state=b.defaultBlockState();return b instanceof StairBlock?state.setValue(StairBlock.FACING,side<0?facing(s).getClockWise():facing(s).getCounterClockWise()):state;}));
  }
  Block timber=p.frame()==null?p.base():p.frame();
  for(int x:new int[]{-2,2})for(int z:new int[]{-2,2})for(int y=0;y<3;y++) {
   BlockPos post=at(s,x,z,y);out.removeIf(st->st.pos().equals(post));out.add(Step.block(post,timber));
  }
  BlockPos window=at(s,0,-2,1);out.removeIf(st->st.pos().equals(window));out.add(Step.block(window,p.window()));
  String color=new String[]{"green","red","orange"}[Math.floorMod(s.kind(),3)];
  Block cloth=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse("minecraft:"+color+"_wool"));
  for(int x=-2;x<=2;x++)for(int z=3;z<=4;z++)out.add(Step.block(at(s,x,z,3),Math.floorMod(x,2)==0?cloth:Blocks.WHITE_WOOL));
  for(int x:new int[]{-2,2})for(int y=0;y<3;y++)out.add(Step.block(at(s,x,4,y),p.fence()));
  out.add(Step.block(stockPos(s),Blocks.CHEST));''').replace('Blocks.WHITE_WOOL','net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse("minecraft:white_wool"))')
# Remove duplicate positions before deriving the stable build order.
s=s.replace('  return out;\n }\n private static Palette', '  java.util.Map<BlockPos,Step> unique=new java.util.LinkedHashMap<>();for(Step st:out)unique.put(st.pos(),st);\n  return new ArrayList<>(unique.values());\n }\n private static Palette',1);p.write_text(s,encoding='utf-8')
