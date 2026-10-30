package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.village.RailTransport;
import de.villagerplus.village.Specials;
import de.villagerplus.village.StockPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.AABB;

/** Träger belädt eine angedockte Truhenlore am Bahnhof mit Nahrung, Holz oder Barren. */
public final class FreightLoading {
	private FreightLoading() { }

	public static boolean docked(MinecartChest cart) {
		RailTransport.Trip trip = cart.getAttached(RailTransport.TRIP);
		return trip != null && !trip.travelling();
	}

	public static boolean work(Ctx c) {
		if (c.village() == null || !Specials.has(c.village(), Blueprints.STATION)) return false;
		BlockPos station = Specials.get(c.village(), Blueprints.STATION);
		if (station == null || !c.level().hasChunkAt(station)) return false;
		MinecartChest cart = c.level().getEntitiesOfClass(MinecartChest.class, new AABB(station).inflate(8, 4, 8), FreightLoading::docked)
			.stream().filter(candidate -> hasRoom(candidate)).findFirst().orElse(null);
		if (cart == null) return false;
		Container chest = communal(c);
		if (chest == null) return false;
		int slot = -1;
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack stack = chest.getItem(i);
			if (stack.isEmpty()) continue;
			var category = StockPolicy.category(stack.getItem());
			if (category == StockPolicy.Category.FOOD || category == StockPolicy.Category.MATERIAL) {
				slot = i;
				break;
			}
		}
		if (slot < 0) return false;
		ItemStack sample = chest.getItem(slot);
		c.setActivity("belädt die Güterlore");
		Anim.hold(c, sample.getItem());
		if (!JobUtil.approach(c, cart.blockPosition(), 3.0)) return true;
		ItemStack moving = chest.removeItem(slot, Math.min(16, chest.getItem(slot).getCount()));
		ItemStack rest = HopperBlockEntity.addItem(null, cart, moving, null);
		if (!rest.isEmpty()) HopperBlockEntity.addItem(null, chest, rest, null);
		chest.setChanged();
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		c.st().cooldownUntil = c.now() + 20;
		return true;
	}

	private static boolean hasRoom(Container cart) {
		for (int i = 0; i < cart.getContainerSize(); i++) if (cart.getItem(i).isEmpty()) return true;
		return false;
	}

	private static Container communal(Ctx c) {
		BlockPos pos = c.village().chestPos(c.level());
		if (pos == null) return null;
		var entity = c.level().getBlockEntity(pos);
		if (entity != null && entity.getAttached(VillagerPlus.OWNER) != null) return null;
		return c.village().chest(c.level());
	}
}
