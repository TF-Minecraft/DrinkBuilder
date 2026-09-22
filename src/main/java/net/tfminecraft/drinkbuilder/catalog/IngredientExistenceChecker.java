package net.tfminecraft.drinkbuilder.catalog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Locale;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import dev.lone.itemsadder.api.CustomStack;
import net.tfminecraft.drinkbuilder.Cache.Ingredient;

/**
 * Verifies configured ingredients still exist in-game before catalog sync.
 */
public final class IngredientExistenceChecker {

	private IngredientExistenceChecker() {}

	public static boolean existsInGame(Ingredient ingredient) {
		if (ingredient == null) {
			return false;
		}
		String token = ingredient.breweryToken == null ? "" : ingredient.breweryToken.trim();
		if (token.isEmpty()) {
			return false;
		}
		String type = ingredient.type == null ? "" : ingredient.type.trim().toLowerCase(Locale.ROOT);
		if ("vanilla".equals(type)) {
			return vanillaExists(token);
		}
		if ("itemsadder".equals(type)) {
			return itemsAdderExists(token);
		}
		if ("mmoitems".equals(type)) {
			return mmoItemsExists(token);
		}
		String lower = token.toLowerCase(Locale.ROOT);
		if (lower.startsWith("itemsadder:")) {
			return itemsAdderExists(token);
		}
		if (lower.startsWith("mmoitems:")) {
			return mmoItemsExists(token);
		}
		return vanillaExists(token);
	}

	private static boolean vanillaExists(String breweryToken) {
		String name = breweryToken.trim();
		if (name.isEmpty()) {
			return false;
		}
		Material material = Material.matchMaterial(name);
		if (material == null) {
			material = Material.matchMaterial(name.replace('_', ' '));
		}
		if (material == null) {
			try {
				material = Material.valueOf(name.toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException ignored) {
				return false;
			}
		}
		return material.isItem() && material != Material.AIR;
	}

	private static boolean itemsAdderExists(String breweryToken) {
		if (Bukkit.getPluginManager().getPlugin("ItemsAdder") == null) {
			return false;
		}
		String path = breweryToken.trim();
		if (path.toLowerCase(Locale.ROOT).startsWith("itemsadder:")) {
			path = path.substring("itemsadder:".length()).trim();
		}
		if (path.isEmpty()) {
			return false;
		}
		try {
			CustomStack stack = CustomStack.getInstance(path);
			if (stack == null) {
				return false;
			}
			ItemStack item = stack.getItemStack();
			return item != null && item.getType() != Material.AIR;
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static boolean mmoItemsExists(String breweryToken) {
		Plugin plugin = Bukkit.getPluginManager().getPlugin("MMOItems");
		if (plugin == null || !plugin.isEnabled()) {
			return false;
		}
		String raw = breweryToken.trim();
		if (raw.toUpperCase(Locale.ROOT).startsWith("MMOITEMS:")) {
			raw = raw.substring("MMOItems:".length()).trim();
		}
		if (raw.isEmpty()) {
			return false;
		}
		if (mmoItemExistsById(raw)) {
			return true;
		}
		return mmoItemExistsById(raw.toUpperCase(Locale.ROOT));
	}

	private static boolean mmoItemExistsById(String itemId) {
		try {
			Class<?> mmoItemsClass = Class.forName("net.Indyuce.mmoitems.MMOItems");
			Field pluginField = mmoItemsClass.getField("plugin");
			Object mmoPlugin = pluginField.get(null);
			if (mmoPlugin == null) {
				return false;
			}
			Object itemManager = mmoPlugin.getClass().getMethod("getItems").invoke(mmoPlugin);
			Object typeManager = mmoPlugin.getClass().getMethod("getTypes").invoke(mmoPlugin);
			if (itemManager == null || typeManager == null) {
				return false;
			}
			Object types = typeManager.getClass().getMethod("getAll").invoke(typeManager);
			if (!(types instanceof Collection<?> allTypes) || allTypes.isEmpty()) {
				return false;
			}
			Object sampleType = allTypes.iterator().next();
			Method getMmoItem = itemManager.getClass().getMethod(
				"getMMOItem",
				sampleType.getClass(),
				String.class
			);
			for (Object type : allTypes) {
				Object mmoItem = getMmoItem.invoke(itemManager, type, itemId);
				if (mmoItem != null) {
					return true;
				}
			}
		} catch (Throwable ignored) {
			return false;
		}
		return false;
	}

	static void logSkip(Logger log, Ingredient ingredient) {
		if (log == null || ingredient == null) {
			return;
		}
		log.info(
			"[catalog] skipping missing ingredient "
				+ ingredient.id
				+ " ("
				+ ingredient.breweryToken
				+ ")"
		);
	}
}
