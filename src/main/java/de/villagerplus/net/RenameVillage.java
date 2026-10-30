package de.villagerplus.net;

import de.villagerplus.VillagerPlus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> Server: Dorf an der Dorftafel umbenennen (leerer Name = prozeduraler Name). */
public record RenameVillage(BlockPos board, String name) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RenameVillage> TYPE =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "rename_village"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RenameVillage> CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, RenameVillage::board, ByteBufCodecs.STRING_UTF8, RenameVillage::name, RenameVillage::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
