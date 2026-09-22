package net.tfminecraft.drinkbuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.tfminecraft.drinkbuilder.objects.PermissionGroupDefinition;

/**
 * Runtime settings loaded from config / ingredients / blacklist YAML.
 */
public final class Cache {

	public static String breweryxFolder = "plugins/BreweryX";
	public static String itemsAdderTfmcDrinks = "plugins/ItemsAdder/contents/tfmc_drinks";

	public static int cmdMin = 20000;
	public static int cmdMax = 29999;

	public static int defaultNameColourStops = 0;
	public static boolean defaultAllowDrinkTexture = false;
	public static List<PermissionGroupDefinition> permissionGroups = List.of();

	public static List<Ingredient> ingredients = Collections.emptyList();
	public static Map<String, String> categories = Map.of();
	public static List<String> effectsBlacklist = Collections.emptyList();
	public static int catalogVersion = 1;

	/** Seconds between iareload and iazip (ArmourShop-style). */
	public static int iaReloadDelaySeconds = 8;

	/** Passive pull interval; 0 disables periodic poll. */
	public static int packPollIntervalSeconds = 120;

	/** Daily HH:mm force pull; empty disables. */
	public static String forceReloadTime = "06:00";

	private Cache() {}

	public static final class Ingredient {
		public final String id;
		public final String type;
		public final String breweryToken;
		public final String label;
		public final String category;

		public Ingredient(
			String id,
			String type,
			String breweryToken,
			String label,
			String category
		) {
			this.id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
			this.type = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
			this.breweryToken = breweryToken == null ? "" : breweryToken.trim();
			this.label = label == null || label.isBlank() ? this.id : label.trim();
			this.category = category == null || category.isBlank() ? "other" : category.trim();
		}
	}

	public static List<String> newStringList() {
		return new ArrayList<>();
	}

	public static List<Ingredient> newIngredientList() {
		return new ArrayList<>();
	}
}
