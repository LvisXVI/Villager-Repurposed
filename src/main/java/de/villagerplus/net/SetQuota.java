package de.villagerplus.net;

import de.villagerplus.VillagerPlus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> Server: Berufsquote des Dorfes um {@code delta} Prozentpunkte aendern (Arbeitsmarkt-Reiter der Dorftafel). */
public record SetQuota(BlockPos board, String job, int delta) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<SetQuota> TYPE =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "set_quota"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SetQuota> CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, SetQuota::board, ByteBufCodecs.STRING_UTF8, SetQuota::job, ByteBufCodecs.VAR_INT, SetQuota::delta, SetQuota::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
