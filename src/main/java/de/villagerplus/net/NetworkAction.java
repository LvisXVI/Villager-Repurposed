package de.villagerplus.net;

import de.villagerplus.VillagerPlus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> Server: Aktion im Nachbarn-Menue der Dorftafel. {@code index} = Eintrag der gespeicherten Link-Liste (keine Zielkoordinaten). */
public record NetworkAction(BlockPos board, String action, int index) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<NetworkAction> TYPE =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "network_action"));
	public static final StreamCodec<RegistryFriendlyByteBuf, NetworkAction> CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, NetworkAction::board, ByteBufCodecs.STRING_UTF8, NetworkAction::action, ByteBufCodecs.VAR_INT, NetworkAction::index, NetworkAction::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
