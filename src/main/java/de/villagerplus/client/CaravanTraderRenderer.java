package de.villagerplus.client;

import de.villagerplus.VillagerPlus;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.WanderingTraderRenderer;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import net.minecraft.resources.Identifier;

/** Keeps vanilla trader item/head layers and animations, with the caravan-specific model and skin. */
public final class CaravanTraderRenderer extends WanderingTraderRenderer {
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(
		VillagerPlus.MOD_ID, "textures/entity/caravan_trader.png");

	public CaravanTraderRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.model = new CaravanTraderModel(context.bakeLayer(CaravanTraderModel.LAYER));
	}

	@Override
	public Identifier getTextureLocation(VillagerRenderState state) {
		return TEXTURE;
	}
}
