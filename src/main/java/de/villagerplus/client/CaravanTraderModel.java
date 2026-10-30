package de.villagerplus.client;

import de.villagerplus.VillagerPlus;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.npc.VillagerModel;
import net.minecraft.resources.Identifier;

/** A traveller's broad-brimmed hat, padded coat and strapped pack, while retaining trader arm animations. */
public final class CaravanTraderModel extends VillagerModel {
	public static final ModelLayerLocation LAYER = new ModelLayerLocation(
		Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "caravan_trader"), "main");

	public CaravanTraderModel(ModelPart root) {
		super(root);
	}

	public static LayerDefinition createBodyLayer() {
		MeshDefinition mesh = VillagerModel.createBodyModel();
		PartDefinition root = mesh.getRoot();
		PartDefinition head = root.getChild("head");
		head.clearChild("hat");
		head.addOrReplaceChild(
			"traveller_crown",
			CubeListBuilder.create().texOffs(32, 0).addBox(-4.0F, -14.0F, -4.0F, 8.0F, 4.0F, 8.0F, new CubeDeformation(0.1F)),
			PartPose.ZERO);
		head.addOrReplaceChild(
			"traveller_brim",
			CubeListBuilder.create().texOffs(0, 52).addBox(-5.0F, -11.0F, -5.0F, 10.0F, 1.0F, 10.0F, new CubeDeformation(0.05F)),
			PartPose.ZERO);

		PartDefinition body = root.getChild("body");
		body.addOrReplaceChild(
			"backpack",
			CubeListBuilder.create().texOffs(32, 38).addBox(-3.5F, 3.0F, 2.5F, 7.0F, 8.0F, 4.0F),
			PartPose.ZERO);
		body.addOrReplaceChild(
			"left_pack_strap",
			CubeListBuilder.create().texOffs(48, 38).addBox(-3.5F, 1.0F, -3.6F, 1.0F, 10.0F, 0.8F),
			PartPose.ZERO);
		body.addOrReplaceChild(
			"right_pack_strap",
			CubeListBuilder.create().texOffs(52, 38).addBox(2.5F, 1.0F, -3.6F, 1.0F, 10.0F, 0.8F),
			PartPose.ZERO);
		body.addOrReplaceChild(
			"left_supply_pouch",
			CubeListBuilder.create().texOffs(40, 51).addBox(-4.5F, 8.0F, -1.5F, 2.0F, 3.0F, 3.0F),
			PartPose.ZERO);
		body.addOrReplaceChild(
			"right_supply_pouch",
			CubeListBuilder.create().texOffs(50, 51).addBox(2.5F, 8.0F, -1.5F, 2.0F, 3.0F, 3.0F),
			PartPose.ZERO);
		return LayerDefinition.create(mesh, 64, 64);
	}
}
