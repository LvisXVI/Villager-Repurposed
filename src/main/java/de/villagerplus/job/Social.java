package de.villagerplus.job;

import de.villagerplus.Config;
import de.villagerplus.VillagerPlus;
import java.util.Comparator;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.npc.villager.Villager;

/** Pausen: Dorfbewohner suchen sich einen Gespraechspartner, stellen sich zusammen, unterhalten sich und tauschen Klatsch aus. */
public final class Social {
	private Social() {
	}

	/** true = gerade im Gespraech oder auf dem Weg dorthin. */
	public static boolean tick(Ctx c) {
		JobState st = c.st();
		Villager v = c.v();
		long now = c.now();

		Villager partner = st.chatPartner;
		if (partner != null && (!partner.isAlive() || partner.isSleeping() || partner.level() != v.level())) {
			end(st);
			partner = null;
		}

		if (partner != null) {
			c.setActivity("plaudert mit " + nameOf(partner));
			boolean close = v.distanceToSqr(partner) <= 3.2 * 3.2;
			if (!close) {
				if (st.chatInitiator && now < st.chatUntil + 400) {
					JobUtil.approach(c, partner.position(), 2.4);
				} else if (now > st.chatUntil + 400) {
					end(st);
				}
				return true;
			}
			v.getLookControl().setLookAt(partner);
			// Etwa alle 2 Sekunden "reden": Laut und Herzchen/Notenteilchen.
			if ((now / Config.JOB_INTERVAL + v.getId()) % 4 == 0) {
				c.level().playSound(null, v.blockPosition(), SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL, 0.7F, 0.9F + v.getRandom().nextFloat() * 0.3F);
				c.level().sendParticles(ParticleTypes.HAPPY_VILLAGER, v.getX(), v.getY() + 2.0, v.getZ(), 1, 0.2, 0.1, 0.2, 0.0);
			}
			if (now >= st.chatUntil) {
				if (st.chatInitiator) {
					v.gossip(c.level(), partner, now);
				}
				c.mem().chats++;
				c.mem().lastChat = now;
				if (partner.getAttachedOrCreate(VillagerPlus.MEMORY) != null) {
					partner.getAttachedOrCreate(VillagerPlus.MEMORY).lastChat = now;
				}
				end(st);
				st.chatCooldown = now + Config.CHAT_COOLDOWN;
			}
			return true;
		}

		if (now < st.chatCooldown) {
			c.setActivity("macht Pause");
			return false;
		}
		Villager other = c.level().getEntitiesOfClass(Villager.class, v.getBoundingBox().inflate(Config.CHAT_RADIUS),
				o -> o != v && o.isAlive() && !o.isBaby() && !o.isSleeping() && !o.isTrading())
			.stream()
			.filter(o -> JobManager.state(o).chatPartner == null && now >= JobManager.state(o).chatCooldown)
			.min(Comparator.comparingDouble(o -> o.distanceToSqr(v))).orElse(null);
		if (other == null) {
			c.setActivity("macht Pause");
			return false;
		}
		JobState os = JobManager.state(other);
		st.chatPartner = other;
		st.chatInitiator = true;
		st.chatUntil = now + Config.CHAT_DURATION;
		os.chatPartner = v;
		os.chatInitiator = false;
		os.chatUntil = now + Config.CHAT_DURATION;
		return true;
	}

	private static void end(JobState st) {
		Villager p = st.chatPartner;
		st.chatPartner = null;
		st.chatInitiator = false;
		if (p != null) {
			JobState ps = JobManager.state(p);
			if (ps.chatPartner != null) {
				ps.chatPartner = null;
				ps.chatInitiator = false;
				ps.chatCooldown = Math.max(ps.chatCooldown, p.level().getGameTime() + Config.CHAT_COOLDOWN);
			}
		}
	}

	private static String nameOf(Villager v) {
		String n = v.getAttachedOrCreate(VillagerPlus.MEMORY).name;
		return n.isEmpty() ? "einem Nachbarn" : n;
	}
}
