package de.villagerplus.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** Persistent, reversible temporary profession change for emergency staffing. */
public record TemporaryAssignment(String original, String target, int level, long until) {
    public static final Codec<TemporaryAssignment> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.STRING.optionalFieldOf("original", "").forGetter(TemporaryAssignment::original),
        Codec.STRING.optionalFieldOf("target", "").forGetter(TemporaryAssignment::target),
        Codec.INT.optionalFieldOf("level", 1).forGetter(TemporaryAssignment::level),
        Codec.LONG.optionalFieldOf("until", 0L).forGetter(TemporaryAssignment::until)
    ).apply(i, TemporaryAssignment::new));

    public static TemporaryAssignment empty() { return new TemporaryAssignment("", "", 1, 0L); }
}
