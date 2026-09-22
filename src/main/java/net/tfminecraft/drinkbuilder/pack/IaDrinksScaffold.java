package net.tfminecraft.drinkbuilder.pack;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Ensure an empty ItemsAdder drinks namespace scaffold exists on disk.
 */
public final class IaDrinksScaffold {

	private IaDrinksScaffold() {}

	public static void ensure(JavaPlugin plugin) {
		if (plugin == null) {
			return;
		}
		String ns = DrinksNamespace.current();
		File root = IaDrinksWriter.resolveDrinksRoot(plugin);
		File configs = new File(root, "configs");
		File itemsYml = new File(configs, "items.yml");
		File textures = new File(root, "resourcepack/" + ns + "/textures/item");

		try {
			if (!configs.exists() && !configs.mkdirs()) {
				plugin.getLogger().warning(
					"[ia] could not create configs dir: " + configs.getAbsolutePath()
				);
				return;
			}
			if (!itemsYml.exists()) {
				String body = ""
					+ "info:\n"
					+ "  namespace: " + ns + "\n"
					+ "items: {}\n";
				Files.writeString(itemsYml.toPath(), body, StandardCharsets.UTF_8);
				plugin.getLogger().info(
					"[ia] wrote empty " + ns + " scaffold: " + itemsYml.getAbsolutePath()
				);
			}
			if (!textures.exists() && !textures.mkdirs()) {
				plugin.getLogger().warning(
					"[ia] could not create textures dir: " + textures.getAbsolutePath()
				);
			}
		} catch (IOException e) {
			plugin.getLogger().warning("[ia] scaffold failed: " + e.getMessage());
		}
	}
}
