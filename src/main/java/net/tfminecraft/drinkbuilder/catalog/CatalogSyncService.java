package net.tfminecraft.drinkbuilder.catalog;

import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.drinkbuilder.Cache;
import net.tfminecraft.drinkbuilder.Cache.Ingredient;
import net.tfminecraft.drinkbuilder.DrinkBuilder;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.CatalogPushResult;

/**
 * Build drink catalog JSON and push to ProvinceSystem (fail-soft).
 */
public final class CatalogSyncService {

	private CatalogSyncService() {}

	public static final class CatalogPayload {
		public final String json;
		public final int ingredientCount;

		public CatalogPayload(String json, int ingredientCount) {
			this.json = json;
			this.ingredientCount = ingredientCount;
		}
	}

	public static CatalogPayload buildPayload() {
		return buildPayload(null);
	}

	public static CatalogPayload buildPayload(Logger log) {
		StringBuilder sb = new StringBuilder(2048);
		sb.append("{\"ingredients\":[");
		boolean first = true;
		int count = 0;
		if (Cache.ingredients != null) {
			for (Ingredient ingredient : Cache.ingredients) {
				if (ingredient == null || ingredient.id.isEmpty()) {
					continue;
				}
				if (!IngredientExistenceChecker.existsInGame(ingredient)) {
					IngredientExistenceChecker.logSkip(log, ingredient);
					continue;
				}
				if (!first) {
					sb.append(',');
				}
				first = false;
				count++;
				sb.append("{\"id\":\"").append(escape(ingredient.id)).append('"');
				sb.append(",\"brewery_token\":\"")
					.append(escape(ingredient.breweryToken)).append('"');
				sb.append(",\"label\":\"").append(escape(ingredient.label)).append('"');
				sb.append(",\"category\":\"")
					.append(escape(ingredient.category)).append('"');
				if (ingredient.type != null && !ingredient.type.isEmpty()) {
					sb.append(",\"type\":\"").append(escape(ingredient.type)).append('"');
				}
				sb.append('}');
			}
		}
		sb.append("],\"categories\":{");
		first = true;
		Map<String, String> categories = Cache.categories;
		if (categories != null) {
			for (Map.Entry<String, String> entry : categories.entrySet()) {
				if (entry.getKey() == null || entry.getKey().isBlank()) {
					continue;
				}
				if (!first) {
					sb.append(',');
				}
				first = false;
				String label = entry.getValue() == null || entry.getValue().isBlank()
					? entry.getKey()
					: entry.getValue();
				sb.append('"').append(escape(entry.getKey())).append("\":\"")
					.append(escape(label)).append('"');
			}
		}
		sb.append("},\"effects_blacklist\":[");
		first = true;
		if (Cache.effectsBlacklist != null) {
			for (String effect : Cache.effectsBlacklist) {
				if (effect == null || effect.isBlank()) {
					continue;
				}
				if (!first) {
					sb.append(',');
				}
				first = false;
				sb.append('"').append(escape(effect.trim().toLowerCase())).append('"');
			}
		}
		sb.append("],\"version\":").append(Cache.catalogVersion).append('}');
		return new CatalogPayload(sb.toString(), count);
	}

	public static void pushAsync(JavaPlugin plugin) {
		pushAsync(plugin, null);
	}

	public static void pushAsync(JavaPlugin plugin, Consumer<CatalogPushResult> onDone) {
		if (plugin == null) {
			return;
		}
		Bukkit.getScheduler().runTask(plugin, () -> {
			Logger log = plugin.getLogger();
			CatalogPayload payload = buildPayload(log);
			Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
				CatalogPushResult result = pushNow(payload);
				Bukkit.getScheduler().runTask(plugin, () -> {
					if (onDone != null) {
						onDone.accept(result);
						return;
					}
					if (result.ok) {
						log.info("[catalog] synced to ProvinceSystem: ingredients="
							+ result.ingredients
							+ (result.updatedAt != null ? (" updated_at=" + result.updatedAt) : ""));
					} else {
						log.warning("[catalog] sync failed: " + result.error);
					}
				});
			});
		});
	}

	public static CatalogPushResult pushNow() {
		return pushNow(buildPayload());
	}

	public static CatalogPushResult pushNow(CatalogPayload payload) {
		if (payload == null) {
			return CatalogPushResult.fail("catalog payload missing");
		}
		return ProvinceSystemClient.pushCatalog(payload.json, payload.ingredientCount);
	}

	public static void pushAsyncFromPlugin() {
		DrinkBuilder plugin = DrinkBuilder.plugin;
		if (plugin != null) {
			pushAsync(plugin);
		}
	}

	private static String escape(String raw) {
		if (raw == null) {
			return "";
		}
		StringBuilder out = new StringBuilder(raw.length() + 8);
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			switch (c) {
				case '\\' -> out.append("\\\\");
				case '"' -> out.append("\\\"");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> out.append(c);
			}
		}
		return out.toString();
	}
}
