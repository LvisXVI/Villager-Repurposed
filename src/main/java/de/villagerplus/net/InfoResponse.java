package de.villagerplus.net;

import de.villagerplus.VillagerPlus;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server -> Client: Zeilen des Steckbriefs (Format {@code Label|Wert|Prozent}). */
public record InfoResponse(List<String> lines) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<InfoResponse> TYPE =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "info_response"));
	public static final StreamCodec<RegistryFriendlyByteBuf, InfoResponse> CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(64)), InfoResponse::lines, InfoResponse::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
