package de.villagerplus.job;

import de.villagerplus.Config;
import de.villagerplus.village.Expedition;
import de.villagerplus.village.Guests;
import java.util.Comparator;
import java.util.function.Predicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

/** Field medicine: uses a real carried/public potion or food to treat injured members of this village. */
public class HealerJob implements Job {
	@Override
	public boolean primary(Ctx c) { return treat(c); }

	@Override
	public boolean secondary(Ctx c) { return treat(c); }

	private static boolean treat(Ctx c) {
		if (c.idle(0) || c.village() == null) return false;
		Villager self = c.v();
		LivingEntity patient = c.st().targetEntity instanceof LivingEntity living && hurtResident(c, living) ? living : null;
		if (patient == null) {
			c.st().clearTarget();
			var bounds = new net.minecraft.world.phys.AABB(c.village().bell()).inflate(Config.VILLAGE_RADIUS);
			patient = c.level().getEntitiesOfClass(LivingEntity.class, bounds,
				e -> e != self && hurtResident(c, e)).stream()
				.min(Comparator.comparingDouble(e -> e.distanceToSqr(self))).orElse(null);
			if (patient == null) { c.setIdle(0, 300); return false; }
			c.st().targetEntity = patient;
		}

		ItemStack medicine = findMedicine(self.getInventory());
		if (medicine.isEmpty()) {
			Container publicChest = JobUtil.box(c);
			ItemStack stock = findMedicine(publicChest);
			if (publicChest == null || stock.isEmpty()) { c.st().clearTarget(); c.setIdle(0, 300); return false; }
			var chestPos = JobUtil.chest(c);
			if (chestPos == null) { c.st().clearTarget(); c.setIdle(0, 300); return false; }
			c.setActivity("holt Arznei aus der Dorfkiste");
			if (!JobUtil.approach(c, chestPos, 3.0)) return true;
			ItemStack picked = takeOne(publicChest, stock);
			if (picked.isEmpty()) { c.st().clearTarget(); return false; }
			JobUtil.give(c.level(), self, picked);
			return true;
		}

		c.setActivity("versorgt einen verletzten Dorfbewohner");
		Anim.hold(c, medicine.getItem());
		if (!JobUtil.approach(c, patient.position(), 3.0)) return true;
		ItemStack consumed = takeOne(self.getInventory(), medicine);
		if (consumed.isEmpty()) { c.st().clearTarget(); return false; }
		PotionContents potion = consumed.get(DataComponents.POTION_CONTENTS);
		if (potion != null && healingPotion(potion)) {
			potion.applyToLivingEntity(patient, 1.0F);
		} else {
			var food = consumed.get(DataComponents.FOOD);
			int nutrition = food == null ? 2 : Math.max(1, food.nutrition());
			patient.heal(nutrition);
		}
		giveUseRemainder(c, consumed);
		c.level().sendParticles(ParticleTypes.HEART, patient.getX(), patient.getY() + 1.2, patient.getZ(), 4, 0.3, 0.4, 0.3, 0.0);
		JobUtil.swing(self);
		c.mem().jobsDone++;
		c.st().clearTarget();
		c.st().cooldownUntil = c.now() + 300;
		return true;
	}

	private static boolean hurtResident(Ctx c, LivingEntity e) {
		if (!e.isAlive() || e.getHealth() >= e.getMaxHealth() || e.distanceToSqr(c.village().bell().getX(), c.village().bell().getY(), c.village().bell().getZ())
			> (double) Config.VILLAGE_RADIUS * Config.VILLAGE_RADIUS * 3.0) return false;
		if (e instanceof Villager v) return !Guests.isGuest(v) && !Expedition.isAway(v);
		return e instanceof IronGolem;
	}

	private static ItemStack findMedicine(Container container) {
		if (container == null) return ItemStack.EMPTY;
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack stack = container.getItem(i);
			if (healingPotion(stack) || Meals.isFood(stack)) return stack.copyWithCount(1);
		}
		return ItemStack.EMPTY;
	}

	private static boolean healingPotion(ItemStack stack) {
		if (!stack.is(Items.POTION) && !stack.is(Items.SPLASH_POTION)) return false;
		PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
		return contents != null && healingPotion(contents);
	}

	private static boolean healingPotion(PotionContents contents) {
		boolean healing = false;
		for (MobEffectInstance effect : contents.getAllEffects()) {
			String id = effect.getEffect().value().getDescriptionId();
			if (id.endsWith(".regeneration") || id.endsWith(".instant_health")) healing = true;
			else if (!effect.getEffect().value().isBeneficial()) return false;
		}
		return healing;
	}

	private static ItemStack takeOne(Container source, ItemStack sample) {
		Predicate<ItemStack> same = s -> ItemStack.isSameItemSameComponents(s, sample);
		for (int i = 0; i < source.getContainerSize(); i++) {
			ItemStack stack = source.getItem(i);
			if (!stack.isEmpty() && same.test(stack)) return source.removeItem(i, 1);
		}
		return ItemStack.EMPTY;
	}

	private static void giveUseRemainder(Ctx c, ItemStack consumed) {
		var remainder = consumed.get(DataComponents.USE_REMAINDER);
		var craftingRemainder = consumed.getItem().getCraftingRemainder();
		ItemStack empty = remainder != null ? remainder.convertInto().create()
			: craftingRemainder == null ? ItemStack.EMPTY : craftingRemainder.create();
		if (!empty.isEmpty()) JobUtil.give(c.level(), c.v(), empty);
	}
}
