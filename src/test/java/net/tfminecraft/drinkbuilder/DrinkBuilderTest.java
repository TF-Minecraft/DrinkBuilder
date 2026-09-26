package net.tfminecraft.drinkbuilder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import org.bukkit.Server;
import org.bukkit.Bukkit;
import org.bukkit.UnsafeValues;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import io.papermc.paper.plugin.provider.classloader.PluginClassLoaderGroup;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

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

class DrinkBuilderTest {
	@TempDir Path directory;
	private DrinkBuilder subject;
	private DrinkBuilder previousPlugin;
	private Logger logger;
	private ConfigLoader config;
	private CategoriesLoader categories;
	private IngredientsLoader ingredients;
	private PermissionGroupsLoader groups;
	private CommandManager commands;
	private PluginManager pluginManager;

	@BeforeEach
	void preparePlugin() throws Exception {
		previousPlugin = DrinkBuilder.plugin;
		// Bukkit owns JavaPlugin construction; isolate its host access while exercising lifecycle methods.
		subject = mock(DrinkBuilder.class, CALLS_REAL_METHODS);
		logger = mock(Logger.class);
		doReturn(directory.resolve("DrinkBuilder").toFile()).when(subject).getDataFolder();
		doReturn(logger).when(subject).getLogger();
		Server server = mock(Server.class);
		pluginManager = mock(PluginManager.class);
		when(server.getPluginManager()).thenReturn(pluginManager);
		doReturn(server).when(subject).getServer();
		doReturn(null).when(subject).getCommand(anyString());
		doReturn(null).when(subject).getResource(anyString());
		doAnswer(invocation -> {
			Files.writeString(subject.getDataFolder().toPath().resolve(invocation.getArgument(0, String.class)), "default");
			return null;
		}).when(subject).saveResource(anyString(), eq(false));
		config = mock(ConfigLoader.class);
		categories = mock(CategoriesLoader.class);
		ingredients = mock(IngredientsLoader.class);
		groups = mock(PermissionGroupsLoader.class);
		commands = mock(CommandManager.class);
		field("configLoader", config);
		field("categoriesLoader", categories);
		field("ingredientsLoader", ingredients);
		field("permissionGroupsLoader", groups);
		field("commandManager", commands);
	}

	@AfterEach
	void restorePlugin() {
		DrinkBuilder.plugin = previousPlugin;
	}

	@Test
	void enableWiresServicesCommandsListenersAndReloadsThenStopsOnce() throws Exception {
		PluginCommand command = mock(PluginCommand.class);
		doReturn(command).when(subject).getCommand("drinkbuilder");
		byte[] bottle = {1, 2, 3};
		doReturn(new ByteArrayInputStream(bottle)).when(subject).getResource("assets/glass_bottle.png");
		doReturn(new ByteArrayInputStream(new byte[] {4, 5})).when(subject).getResource("assets/potion_overlay.png");
		try (Services services = new Services()) {
			subject.onEnable();
			assertSame(subject, DrinkBuilder.plugin);
			CmdAllocator allocator = services.allocators.constructed().getFirst();
			PendingReloadQueue queue = services.queues.constructed().getFirst();
			DeferredDrinkIaReload deferred = services.reloads.constructed().getFirst();
			PackPullScheduler scheduler = services.schedulers.constructed().getFirst();
			assertSame(allocator, subject.getCmdAllocator());
			assertSame(deferred, subject.getDeferredIaReload());
			verify(allocator).reloadBounds();
			verify(queue).load();
			verify(scheduler).start();
			verify(command).setExecutor(commands);
			verify(command).setTabCompleter(commands);
			verify(pluginManager).registerEvents(deferred, subject);
			services.listeners.verify(() -> ItemsAdderPackListener.registerIfPresent(deferred));
			services.catalog.verify(() -> CatalogSyncService.pushAsync(subject));
			services.assets.verify(() -> AssetSyncService.pushAsync(subject));
			services.scaffold.verify(() -> IaDrinksScaffold.ensure(subject), times(2));
			verify(logger).info(contains("DrinkBuilder enabled (ingredients="));
			assertArrayEquals(bottle, Files.readAllBytes(subject.getDataFolder().toPath().resolve("assets/glass_bottle.png")));
			assertArrayEquals(new byte[] {4, 5}, Files.readAllBytes(subject.getDataFolder().toPath().resolve("assets/potion_overlay.png")));
			verifyLoaderPaths(1);
			subject.reloadAll();
			verifyLoaderPaths(2);
			verify(allocator, times(2)).reloadBounds();
			verify(scheduler, times(2)).start();
			subject.onDisable();
			subject.onDisable();
			verify(scheduler).stop();
			assertNull(DrinkBuilder.plugin);
		}
	}

	@Test
	void keepsExistingConfigurationAndAssetsAndReportsMissingCommand() throws Exception {
		Path folder = subject.getDataFolder().toPath();
		Files.createDirectories(folder.resolve("assets"));
		for (String name : List.of("config.yml", "ingredients.yml", "effects-blacklist.yml", "categories.yml", "permission-groups.yml")) {
			Files.writeString(folder.resolve(name), "user configuration");
		}
		Files.writeString(folder.resolve("assets/glass_bottle.png"), "existing texture");
		try (Services services = new Services()) {
			subject.onEnable();
			verify(subject, never()).saveResource(anyString(), anyBoolean());
			verify(subject, never()).getResource("assets/glass_bottle.png");
			verify(subject).getResource("assets/potion_overlay.png");
			assertEquals("existing texture", Files.readString(folder.resolve("assets/glass_bottle.png")));
			assertEquals("user configuration", Files.readString(folder.resolve("config.yml")));
			assertFalse(Files.exists(folder.resolve("assets/potion_overlay.png")));
			verify(logger).severe("Command drinkbuilder missing from plugin.yml");
		}
	}

	@Test
	void assetCopyFailureLogsWarningAndClosesInput() throws Exception {
		InputStream broken = mock(InputStream.class, CALLS_REAL_METHODS);
		doThrow(new IOException("broken stream")).when(broken).read(any(byte[].class), anyInt(), anyInt());
		doReturn(broken).when(subject).getResource("assets/glass_bottle.png");
		try (Services services = new Services()) {
			subject.onEnable();
			verify(broken).close();
			verify(logger).warning("Could not copy asset glass_bottle.png: broken stream");
			assertSame(subject, DrinkBuilder.plugin);
		}
	}

	@Test
	void assetDirectoryCreationFailureIsReportedWithoutOpeningResources() throws Exception {
		File fakeFolder = directory.resolve("blocker/DrinkBuilder").toFile();
		Files.writeString(directory.resolve("blocker"), "file");
		doReturn(fakeFolder).when(subject).getDataFolder();
		doNothing().when(subject).saveResource(anyString(), anyBoolean());
		try (Services services = new Services()) {
			subject.onEnable();
			verify(logger, times(2)).warning("Could not create assets folder");
			verify(subject, never()).getResource(anyString());
		}
	}

	@Test
	void disablingBeforeEnableIsSafeAndReloadWorksBeforeServicesExist() throws Exception {
		assertNull(subject.getCmdAllocator());
		assertNull(subject.getDeferredIaReload());
		try (MockedStatic<IaDrinksScaffold> scaffold = mockStatic(IaDrinksScaffold.class)) {
			subject.reloadAll();
			verifyLoaderPaths(1);
			scaffold.verify(() -> IaDrinksScaffold.ensure(subject));
		}
		DrinkBuilder.plugin = subject;
		subject.onDisable();
		assertNull(DrinkBuilder.plugin);
	}

	@Test
	void constructorInitializesLoaderAndCommandCollaborators() throws Exception {
		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getUnsafe).thenReturn(mock(UnsafeValues.class));
			Class<?> pluginType = new TestPluginLoader().definePluginSubclass();
			DrinkBuilder constructed = (DrinkBuilder) pluginType.getConstructor().newInstance();
			for (String name : List.of("configLoader", "categoriesLoader", "ingredientsLoader", "permissionGroupsLoader", "commandManager")) {
				Field collaborator = DrinkBuilder.class.getDeclaredField(name);
				collaborator.setAccessible(true);
				assertNotNull(collaborator.get(constructed), name);
			}
			assertNull(constructed.getCmdAllocator());
			assertNull(constructed.getDeferredIaReload());
		}
	}

	public static class PluginSubclass extends DrinkBuilder {}

	/** Supplies Paper's construction contract without booting a Minecraft server. */
	private static final class TestPluginLoader extends ClassLoader implements ConfiguredPluginClassLoader {
		TestPluginLoader() { super(DrinkBuilderTest.class.getClassLoader()); }
		Class<?> definePluginSubclass() throws IOException {
			String name = DrinkBuilderTest.class.getName() + "$PluginSubclass";
			try (InputStream input = getResourceAsStream(name.replace('.', '/') + ".class")) {
				assertNotNull(input);
				byte[] bytes = input.readAllBytes();
				return defineClass(name, bytes, 0, bytes.length);
			}
		}
		@Override public PluginMeta getConfiguration() { return null; }
		@Override public Class<?> loadClass(String name, boolean resolve, boolean checkGlobal, boolean checkLibraries) throws ClassNotFoundException {
			return super.loadClass(name, resolve);
		}
		@Override public void init(JavaPlugin plugin) {}
		@Override public JavaPlugin getPlugin() { return null; }
		@Override public PluginClassLoaderGroup getGroup() { return null; }
		@Override public void close() {}
	}

	private void verifyLoaderPaths(int count) {
		verify(config, times(count)).load(new File(subject.getDataFolder(), "config.yml"));
		verify(groups, times(count)).load(new File(subject.getDataFolder(), "permission-groups.yml"));
		verify(categories, times(count)).load(new File(subject.getDataFolder(), "categories.yml"));
		verify(ingredients, times(count)).loadIngredients(new File(subject.getDataFolder(), "ingredients.yml"));
		verify(ingredients, times(count)).loadEffectsBlacklist(new File(subject.getDataFolder(), "effects-blacklist.yml"));
	}

	private void field(String name, Object value) throws Exception {
		Field field = DrinkBuilder.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(subject, value);
	}

	private static final class Services implements AutoCloseable {
		final MockedConstruction<CmdAllocator> allocators = mockConstruction(CmdAllocator.class);
		final MockedConstruction<PendingReloadQueue> queues = mockConstruction(PendingReloadQueue.class);
		final MockedConstruction<DeferredDrinkIaReload> reloads = mockConstruction(DeferredDrinkIaReload.class);
		final MockedConstruction<PackPullScheduler> schedulers = mockConstruction(PackPullScheduler.class);
		final MockedStatic<IaDrinksScaffold> scaffold = mockStatic(IaDrinksScaffold.class);
		final MockedStatic<ItemsAdderPackListener> listeners = mockStatic(ItemsAdderPackListener.class);
		final MockedStatic<CatalogSyncService> catalog = mockStatic(CatalogSyncService.class);
		final MockedStatic<AssetSyncService> assets = mockStatic(AssetSyncService.class);

		@Override
		public void close() {
			assets.close();
			catalog.close();
			listeners.close();
			scaffold.close();
			schedulers.close();
			reloads.close();
			queues.close();
			allocators.close();
		}
	}
}
