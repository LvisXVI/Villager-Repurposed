package de.villagerplus.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Persisted conservative union of a village's original and mod-built footprint. */
public record VillageExtent(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
	public static final Codec<VillageExtent> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.INT.fieldOf("min_x").forGetter(VillageExtent::minX), Codec.INT.fieldOf("min_y").forGetter(VillageExtent::minY),
		Codec.INT.fieldOf("min_z").forGetter(VillageExtent::minZ), Codec.INT.fieldOf("max_x").forGetter(VillageExtent::maxX),
		Codec.INT.fieldOf("max_y").forGetter(VillageExtent::maxY), Codec.INT.fieldOf("max_z").forGetter(VillageExtent::maxZ)
	).apply(i, VillageExtent::new));

	public VillageExtent {
		int x0 = Math.min(minX, maxX), x1 = Math.max(minX, maxX);
		int y0 = Math.min(minY, maxY), y1 = Math.max(minY, maxY);
		int z0 = Math.min(minZ, maxZ), z1 = Math.max(minZ, maxZ);
		minX = x0; maxX = x1; minY = y0; maxY = y1; minZ = z0; maxZ = z1;
	}

	public static VillageExtent of(BoundingBox box) {
		return new VillageExtent(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
	}

	public VillageExtent union(BoundingBox box) {
		return new VillageExtent(Math.min(minX, box.minX()), Math.min(minY, box.minY()), Math.min(minZ, box.minZ()),
			Math.max(maxX, box.maxX()), Math.max(maxY, box.maxY()), Math.max(maxZ, box.maxZ()));
	}

	public BoundingBox box() { return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ); }
}
