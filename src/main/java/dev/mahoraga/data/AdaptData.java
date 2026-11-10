package dev.mahoraga.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashMap;
import java.util.Map;

/** Data adaptasi per pemain (disimpan sebagai attachment). Nilai = persen. */
public class AdaptData {
    public static final Codec<Map<String, Float>> MAP_CODEC = Codec.unboundedMap(Codec.STRING, Codec.FLOAT);

    public static final Codec<AdaptData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.optionalFieldOf("wheel", false).forGetter(d -> d.wheel),
            MAP_CODEC.optionalFieldOf("defense", Map.<String, Float>of()).forGetter(d -> d.defense),
            MAP_CODEC.optionalFieldOf("attack", Map.<String, Float>of()).forGetter(d -> d.attack)
    ).apply(i, (w, d, a) -> new AdaptData(w, d, a)));

    public boolean wheel;
    public final Map<String, Float> defense = new HashMap<>();
    public final Map<String, Float> attack = new HashMap<>();

    public AdaptData() {}

    private AdaptData(boolean wheel, Map<String, Float> d, Map<String, Float> a) {
        this.wheel = wheel;
        this.defense.putAll(d);
        this.attack.putAll(a);
    }

    public Map<String, Float> track(String name) {
        return name.equals("attack") ? attack : defense;
    }
}
