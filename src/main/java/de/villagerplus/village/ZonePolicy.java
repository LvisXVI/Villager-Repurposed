package de.villagerplus.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import de.villagerplus.VillagerPlus;
import de.villagerplus.net.SetZone;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Persistent, bounded map zones attached to their owning village bell. */
public final class ZonePolicy {
	public static final int MAX_ZONES = 32;
	public static final int MAX_RADIUS = 64;
	public record Zone(String id, String kind, int minX, int minZ, int maxX, int maxZ) {
		public static final Codec<Zone> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("id").forGetter(Zone::id), Codec.STRING.fieldOf("kind").forGetter(Zone::kind),
			Codec.INT.fieldOf("min_x").forGetter(Zone::minX), Codec.INT.fieldOf("min_z").forGetter(Zone::minZ),
			Codec.INT.fieldOf("max_x").forGetter(Zone::maxX), Codec.INT.fieldOf("max_z").forGetter(Zone::maxZ)
		).apply(i, Zone::new));
	}
	private ZonePolicy() { }

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(SetZone.TYPE, SetZone.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(SetZone.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (!(player.level() instanceof ServerLevel level) || player.isSpectator() || player.blockPosition().distSqr(payload.board()) > 24L * 24L
				|| !level.hasChunkAt(payload.board()) || !(level.getBlockState(payload.board()).getBlock() instanceof de.villagerplus.block.DorftafelBlock)) return;
			VillageRef ref = Villages.villageNear(level, payload.board(), 96);
			if (ref == null) return;
			boolean accepted = payload.delete() ? delete(ref, payload.kind()) : add(ref, payload);
			if (!accepted) VillageMessages.system(player, net.minecraft.network.chat.Component.literal("Zone abgelehnt: Grenzen oder Anzahl sind ungültig."));
			de.villagerplus.block.DorftafelBlock.open(level, player, payload.board());
		});
	}

	private static boolean add(VillageRef ref, SetZone p) {
		int minX = Math.min(p.minX(), p.maxX()), maxX = Math.max(p.minX(), p.maxX());
		int minZ = Math.min(p.minZ(), p.maxZ()), maxZ = Math.max(p.minZ(), p.maxZ());
		if (!validBounds(ref, p.kind(), minX, minZ, maxX, maxZ)) return false;
		List<Zone> zones = new ArrayList<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES, List.of()));
		if (zones.size() >= MAX_ZONES) return false;
		zones.add(new Zone(UUID.randomUUID().toString(), p.kind(), minX, minZ, maxX, maxZ));
		ref.bellEntity().setAttached(VillagerPlus.ZONES, List.copyOf(zones)); ref.dirty(); return true;
	}
	public static boolean validBounds(VillageRef ref, String kind, int minX, int minZ, int maxX, int maxZ) {
		if (ref == null || !("farm".equals(kind) || "housing".equals(kind) || "work".equals(kind) || "pasture".equals(kind)) || minX > maxX || minZ > maxZ) return false;
		return Math.abs((long)minX - ref.bell().getX()) <= MAX_RADIUS && Math.abs((long)maxX - ref.bell().getX()) <= MAX_RADIUS
			&& Math.abs((long)minZ - ref.bell().getZ()) <= MAX_RADIUS && Math.abs((long)maxZ - ref.bell().getZ()) <= MAX_RADIUS
			&& (long)(maxX - minX + 1) * (maxZ - minZ + 1) <= 4096;
	}
	private static boolean delete(VillageRef ref, String id) {
		if (id == null || id.length() > 36) return false;
		List<Zone> zones = new ArrayList<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES, List.of()));
		boolean removed = zones.removeIf(z -> z.id().equals(id));
		if (removed) { ref.bellEntity().setAttached(VillagerPlus.ZONES, List.copyOf(zones)); ref.dirty(); }
		return removed;
	}
	public static List<String> rows(VillageRef ref) {
		return ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES, List.of()).stream()
			.map(z -> "@z|" + z.id() + "|" + z.kind() + "|" + z.minX() + "|" + z.minZ() + "|" + z.maxX() + "|" + z.maxZ()).toList();
	}
}
