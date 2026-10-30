package de.villagerplus.net;

import de.villagerplus.VillagerPlus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> Server: "Schick mir die Dorfdaten der Dorftafel an dieser Position neu." */
public record BoardRequest(BlockPos pos) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<BoardRequest> TYPE =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "board_request"));
	public static final StreamCodec<RegistryFriendlyByteBuf, BoardRequest> CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, BoardRequest::pos, BoardRequest::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
