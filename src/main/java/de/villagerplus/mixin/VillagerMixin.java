package de.villagerplus.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.villagerplus.Config;
import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.village.Wellbeing;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.gossip.GossipContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Laengeres Gedaechtnis und Stimmungspreise. Beide Eingriffe sind bewusst kompatibel gehalten: {@code @WrapOperation} (MixinExtras)
 * statt {@code @Redirect}, damit andere Mods dieselbe Stelle ebenfalls einhaken koennen, und {@code require = 0}, damit eine Mod, die
 * die Zielmethode umbaut, nicht zum Absturz fuehrt (der jeweilige Zusatz entfaellt dann einfach).
 */
@Mixin(Villager.class)
public abstract class VillagerMixin {
	/** Das taegliche Vergessen von Ruf/Klatsch passiert nur jeden N-ten Tag. */
	@WrapOperation(method = "maybeDecayGossip",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/gossip/GossipContainer;decay()V"), require = 0)
	private void villagerplus$slowDecay(GossipContainer gossips, Operation<Void> original) {
		VillagerMemory memory = ((Villager) (Object) this).getAttachedOrCreate(VillagerPlus.MEMORY);
		memory.gossipDays++;
		if (memory.gossipDays % Config.GOSSIP_DECAY_DIVISOR == 0) {
			original.call(gossips);
		}
	}

	/** Stimmung wirkt auf die Preise: gute Laune = kleiner Rabatt, schlechte Laune = Aufschlag. */
	@Inject(method = "updateSpecialPrices", at = @At("TAIL"), require = 0)
	private void villagerplus$moodPrices(Player player, CallbackInfo ci) {
		Villager self = (Villager) (Object) this;
		if (!(self.level() instanceof ServerLevel level)) {
			return;
		}
		double mod = Wellbeing.priceModifier(Wellbeing.of(level, self, self.getAttachedOrCreate(VillagerPlus.MEMORY)));
		if (mod == 0.0) {
			return;
		}
		for (MerchantOffer offer : self.getOffers()) {
			int base = offer.getBaseCostA().getCount();
			int diff = (int) Math.max(1, Math.ceil(base * Math.abs(mod)));
			offer.addToSpecialPriceDiff(mod < 0 ? -diff : diff);
		}
	}
}
