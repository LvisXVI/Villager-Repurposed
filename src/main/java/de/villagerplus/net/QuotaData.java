package de.villagerplus.net;

import de.villagerplus.VillagerPlus;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server -> Client: Arbeitsmarkt des Dorfes, Zeilen {@code schluessel|name|anzahl|sollprozent|standardprozent}. */
public record QuotaData(List<String> rows) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<QuotaData> TYPE =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "quota_data"));
	public static final StreamCodec<RegistryFriendlyByteBuf, QuotaData> CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(200)), QuotaData::rows, QuotaData::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
