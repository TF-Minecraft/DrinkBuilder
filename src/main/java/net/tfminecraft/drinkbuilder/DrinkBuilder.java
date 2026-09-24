package net.tfminecraft.drinkbuilder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.drinkbuilder.catalog.AssetSyncService;
import net.tfminecraft.drinkbuilder.catalog.CatalogSyncService;
import net.tfminecraft.drinkbuilder.loaders.CategoriesLoader;
import net.tfminecraft.drinkbuilder.loaders.ConfigLoader;
import net.tfminecraft.drinkbuilder.loaders.IngredientsLoader;
import net.tfminecraft.drinkbuilder.loaders.PermissionGroupsLoader;
import net.tfminecraft.drinkbuilder.managers.CommandManager;
import net.tfminecraft.drinkbuilder.pack.CmdAllocator;
import net.tfminecraft.drinkbuilder.pack.DeferredDrinkIaReload;
import net.tfminecraft.drinkbuilder.pack.IaDrinksScaffold;
import net.tfminecraft.drinkbuilder.pack.ItemsAdderPackListener;
import net.tfminecraft.drinkbuilder.pack.PackPullScheduler;
import net.tfminecraft.drinkbuilder.pack.PendingReloadQueue;

public class DrinkBuilder extends JavaPlugin {

	public static DrinkBuilder plugin;

	private final ConfigLoader configLoader = new ConfigLoader();
	private final IngredientsLoader ingredientsLoader = new IngredientsLoader();
	private final CategoriesLoader categoriesLoader = new CategoriesLoader();
	private final PermissionGroupsLoader permissionGroupsLoader = new PermissionGroupsLoader();
	private final CommandManager commandManager = new CommandManager();
	private CmdAllocator cmdAllocator;
	private PendingReloadQueue pendingReloadQueue;
	private DeferredDrinkIaReload deferredIaReload;
	private PackPullScheduler packPullScheduler;

	@Override
	public void onEnable() {
		plugin = this;
		if (!getDataFolder().exists()) {
			getDataFolder().mkdirs();
		}
		saveDefaultConfigs();
		reloadAll();
		cmdAllocator = new CmdAllocator(this);
		cmdAllocator.reloadBounds();

		pendingReloadQueue = new PendingReloadQueue(this);
		pendingReloadQueue.load();
		deferredIaReload = new DeferredDrinkIaReload(this, pendingReloadQueue);
		packPullScheduler = new PackPullScheduler(this);
		packPullScheduler.start();

		IaDrinksScaffold.ensure(this);

		if (getCommand(commandManager.cmd1) != null) {
			getCommand(commandManager.cmd1).setExecutor(commandManager);
			getCommand(commandManager.cmd1).setTabCompleter(commandManager);
		} else {
			getLogger().severe("Command drinkbuilder missing from plugin.yml");
		}

		getServer().getPluginManager().registerEvents(deferredIaReload, this);
		ItemsAdderPackListener.registerIfPresent(deferredIaReload);
		CatalogSyncService.pushAsync(this);
		AssetSyncService.pushAsync(this);

		getLogger().info(
			"DrinkBuilder enabled (ingredients="
				+ Cache.ingredients.size()
				+ ", categories="
				+ Cache.categories.size()
				+ ", groups="
				+ Cache.permissionGroups.size()
				+ ", blacklist="
				+ Cache.effectsBlacklist.size()
				+ ", nextCmd="
				+ cmdAllocator.peekNext()
				+ ", pendingIa="
				+ pendingReloadQueue.size()
				+ ")"
		);
	}

	@Override
	public void onDisable() {
		if (packPullScheduler != null) {
			packPullScheduler.stop();
			packPullScheduler = null;
		}
		plugin = null;
	}

	public CmdAllocator getCmdAllocator() {
		return cmdAllocator;
	}

	public DeferredDrinkIaReload getDeferredIaReload() {
		return deferredIaReload;
	}

	public void reloadAll() {
		configLoader.load(new File(getDataFolder(), "config.yml"));
		permissionGroupsLoader.load(new File(getDataFolder(), "permission-groups.yml"));
		categoriesLoader.load(new File(getDataFolder(), "categories.yml"));
		ingredientsLoader.loadIngredients(new File(getDataFolder(), "ingredients.yml"));
		ingredientsLoader.loadEffectsBlacklist(
			new File(getDataFolder(), "effects-blacklist.yml")
		);
		if (cmdAllocator != null) {
			cmdAllocator.reloadBounds();
		}
		if (packPullScheduler != null) {
			packPullScheduler.start();
		}
		IaDrinksScaffold.ensure(this);
	}

	private void saveDefaultConfigs() {
		saveIfMissing("config.yml");
		saveIfMissing("ingredients.yml");
		saveIfMissing("effects-blacklist.yml");
		saveIfMissing("categories.yml");
		saveIfMissing("permission-groups.yml");
		saveAssetIfMissing("glass_bottle.png");
		saveAssetIfMissing("potion_overlay.png");
	}

	private void saveIfMissing(String name) {
		File out = new File(getDataFolder(), name);
		if (!out.exists()) {
			saveResource(name, false);
		}
	}

	private void saveAssetIfMissing(String fileName) {
		File assetsDir = new File(getDataFolder(), "assets");
		if (!assetsDir.exists() && !assetsDir.mkdirs()) {
			getLogger().warning("Could not create assets folder");
			return;
		}
		File out = new File(assetsDir, fileName);
		if (out.exists()) {
			return;
		}
		String resourcePath = "assets/" + fileName;
		try (InputStream in = getResource(resourcePath)) {
			if (in == null) {
				return;
			}
			Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			getLogger().warning("Could not copy asset " + fileName + ": " + e.getMessage());
		}
	}
}
