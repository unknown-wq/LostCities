package com.lostbuildings.engine.codec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The weighted candidate lists of a city style: which buildings, multi-buildings, parks, fountains,
 * bridges, stairs and fronts it may use.
 *
 * <p>Reduced port: the original's {@code raildungeons} list is dropped along with the rest of the
 * railway (phase 4). Everything else is kept, because §6 hands all of it to agent G.
 *
 * <p>A {@code null} list means "inherit"; an <em>empty</em> list means "this style deliberately has
 * none" — {@code citystyle_border} ships {@code "multibuildings": []} exactly to say that, and the
 * merge below has to preserve the difference.
 */
public record SelectorsRE(List<ObjectSelector> buildings,
                          List<ObjectSelector> multiBuildings,
                          List<ObjectSelector> parks,
                          List<ObjectSelector> fountains,
                          List<ObjectSelector> bridges,
                          List<ObjectSelector> stairs,
                          List<ObjectSelector> fronts) {

	public static final Codec<SelectorsRE> CODEC = RecordCodecBuilder.create(instance ->
			instance.group(
					list("buildings", SelectorsRE::buildings),
					list("multibuildings", SelectorsRE::multiBuildings),
					list("parks", SelectorsRE::parks),
					list("fountains", SelectorsRE::fountains),
					list("bridges", SelectorsRE::bridges),
					list("stairs", SelectorsRE::stairs),
					list("fronts", SelectorsRE::fronts)
			).apply(instance, (b, m, p, f, br, s, fr) -> new SelectorsRE(
					b.orElse(null), m.orElse(null), p.orElse(null), f.orElse(null),
					br.orElse(null), s.orElse(null), fr.orElse(null))));

	private static RecordCodecBuilder<SelectorsRE, Optional<List<ObjectSelector>>> list(
			String field, java.util.function.Function<SelectorsRE, List<ObjectSelector>> getter) {
		return Codec.list(ObjectSelector.CODEC).optionalFieldOf(field)
				.forGetter(s -> Optional.ofNullable(getter.apply(s)));
	}

	/** Per-list override: a list the child defines (even an empty one) replaces the parent's. */
	public static SelectorsRE merge(SelectorsRE child, SelectorsRE parent) {
		if (child == null) {
			return parent;
		}
		if (parent == null) {
			return child;
		}
		return new SelectorsRE(
				child.buildings != null ? child.buildings : parent.buildings,
				child.multiBuildings != null ? child.multiBuildings : parent.multiBuildings,
				child.parks != null ? child.parks : parent.parks,
				child.fountains != null ? child.fountains : parent.fountains,
				child.bridges != null ? child.bridges : parent.bridges,
				child.stairs != null ? child.stairs : parent.stairs,
				child.fronts != null ? child.fronts : parent.fronts);
	}

	/** All non-null lists keyed by their JSON field name. */
	public Map<String, List<ObjectSelector>> asMap() {
		Map<String, List<ObjectSelector>> map = new LinkedHashMap<>();
		put(map, "buildings", buildings);
		put(map, "multibuildings", multiBuildings);
		put(map, "parks", parks);
		put(map, "fountains", fountains);
		put(map, "bridges", bridges);
		put(map, "stairs", stairs);
		put(map, "fronts", fronts);
		return Map.copyOf(map);
	}

	private static void put(Map<String, List<ObjectSelector>> map, String key, List<ObjectSelector> value) {
		if (value != null) {
			map.put(key, List.copyOf(value));
		}
	}
}
