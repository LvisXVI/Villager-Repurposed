package de.villagerplus.net;

import de.villagerplus.VillagerPlus;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> Server: "Gib mir den Steckbrief des Villagers, mit dem ich gerade handle." */
public record InfoRequest() implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<InfoRequest> TYPE =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "info_request"));
	public static final StreamCodec<RegistryFriendlyByteBuf, InfoRequest> CODEC = StreamCodec.unit(new InfoRequest());

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
