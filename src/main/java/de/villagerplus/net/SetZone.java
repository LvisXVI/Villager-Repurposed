package de.villagerplus.net;

import de.villagerplus.VillagerPlus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client request to create/delete a bounded village map zone. */
public record SetZone(BlockPos board, int minX, int minZ, int maxX, int maxZ, String kind, boolean delete) implements CustomPacketPayload {
	public static final Type<SetZone> TYPE = new Type<>(Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "set_zone"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SetZone> CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, SetZone::board, ByteBufCodecs.VAR_INT, SetZone::minX, ByteBufCodecs.VAR_INT, SetZone::minZ,
		ByteBufCodecs.VAR_INT, SetZone::maxX, ByteBufCodecs.VAR_INT, SetZone::maxZ, ByteBufCodecs.STRING_UTF8, SetZone::kind,
		ByteBufCodecs.BOOL, SetZone::delete, SetZone::new);
	@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
