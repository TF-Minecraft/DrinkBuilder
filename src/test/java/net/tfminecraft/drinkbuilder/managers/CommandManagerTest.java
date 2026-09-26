package net.tfminecraft.drinkbuilder.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import net.tfminecraft.drinkbuilder.Cache;
import net.tfminecraft.drinkbuilder.DrinkBuilder;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.CatalogPushResult;
import net.tfminecraft.drinkbuilder.catalog.AssetSyncService;
import net.tfminecraft.drinkbuilder.catalog.CatalogSyncService;
import net.tfminecraft.drinkbuilder.pack.CmdAllocator;
import net.tfminecraft.drinkbuilder.pack.DeletableDrinkCache;
import net.tfminecraft.drinkbuilder.pack.DrinkDeleteRunner;
import net.tfminecraft.drinkbuilder.pack.PackPullRunner;
import net.tfminecraft.drinkbuilder.pack.PackPullRunner.PullResult;
import net.tfminecraft.drinkbuilder.pack.PackReapplyRunner;
import net.tfminecraft.drinkbuilder.pack.PackReapplyRunner.ReapplyResult;
import net.tfminecraft.drinkbuilder.utils.Permissions;

@SuppressWarnings("deprecation")
class CommandManagerTest {
	private final CommandManager manager = new CommandManager();
	private final CommandSender sender = mock(CommandSender.class);
	private DrinkBuilder savedPlugin;

	@BeforeEach
	void setUp() {
		savedPlugin = DrinkBuilder.plugin;
		DrinkBuilder.plugin = null;
		when(sender.hasPermission(Permissions.ADMIN)).thenReturn(true);
	}

	@AfterEach
	void restorePlugin() {
		DrinkBuilder.plugin = savedPlugin;
	}

	@Test
	void commandPermissionIsRequiredBeforeAnyAction() {
		when(sender.hasPermission(Permissions.ADMIN)).thenReturn(false);
		command("reload");
		message(ChatColor.RED, "You do not have permission to use /drinkbuilder.");
		verify(sender).hasPermission(Permissions.ADMIN);
		verifyNoMoreInteractions(sender);
	}

	@Test
	void emptyAndUnknownCommandsDisplayUsage() {
		String usage = "Usage: /drinkbuilder reload|catalog sync|pack pull [force]|pack reapply <id>|drink delete <id>";
		command();
		message(ChatColor.AQUA, usage);
		clearInvocations(sender);
		command("unknown");
		message(ChatColor.AQUA, usage);
	}

	@Test
	void incompleteAndInvalidSubcommandsDisplayTheirUsage() {
		for (String[] args : List.of(new String[]{"catalog"}, new String[]{"catalog", "other"})) {
			command(args);
			message(ChatColor.AQUA, "Usage: /drinkbuilder catalog sync");
			clearInvocations(sender);
		}
		for (String[] args : List.of(new String[]{"pack"}, new String[]{"pack", "other"})) {
			command(args);
			message(ChatColor.AQUA, "Usage: /drinkbuilder pack pull [force]|pack reapply <id>");
			clearInvocations(sender);
		}
		for (String[] args : List.of(new String[]{"pack", "reapply"}, new String[]{"pack", "reapply", " "})) {
			command(args);
			message(ChatColor.AQUA, "Usage: /drinkbuilder pack reapply <id>");
			clearInvocations(sender);
		}
		for (String[] args : List.of(new String[]{"drink"}, new String[]{"drink", "delete"},
			new String[]{"drink", "other", "one"})) {
			command(args);
			message(ChatColor.AQUA, "Usage: /drinkbuilder drink delete <id>");
			clearInvocations(sender);
		}
	}

	@Test
	void commandsThatNeedPluginReportItIsNotReady() {
		for (String[] args : List.of(new String[]{"reload"}, new String[]{"catalog", "sync"},
			new String[]{"pack", "reapply", "one"}, new String[]{"drink", "delete", "one"})) {
			command(args);
			message(ChatColor.RED, "Plugin not ready.");
			clearInvocations(sender);
		}
	}

	@Test
	void reloadRefreshesConfigurationAndStartsBothSyncs() {
		var plugin = mock(DrinkBuilder.class);
		var allocator = mock(CmdAllocator.class);
		DrinkBuilder.plugin = plugin;
		when(plugin.getCmdAllocator()).thenReturn(allocator);
		when(allocator.peekNext()).thenReturn(21000);
		try (var catalog = mockStatic(CatalogSyncService.class); var assets = mockStatic(AssetSyncService.class)) {
			command(" ReLoAd ");
			verify(plugin).reloadAll();
			message(ChatColor.GREEN, "DrinkBuilder reloaded (" + Cache.ingredients.size() + " ingredients, "
				+ Cache.categories.size() + " categories, " + Cache.permissionGroups.size() + " permission groups, "
				+ Cache.effectsBlacklist.size() + " blacklisted effects). Next CMD: 21000");
			catalog.verify(() -> CatalogSyncService.pushAsync(plugin));
			assets.verify(() -> AssetSyncService.pushAsync(plugin));
		}
	}

	@Test
	void catalogSyncReportsSuccessAndFailureAndStartsAssetSyncAfterEachCallback() {
		var plugin = mock(DrinkBuilder.class);
		DrinkBuilder.plugin = plugin;
		try (var catalog = mockStatic(CatalogSyncService.class); var assets = mockStatic(AssetSyncService.class)) {
			for (var result : List.of(CatalogPushResult.success(4, "today"), CatalogPushResult.fail("offline"))) {
				@SuppressWarnings("unchecked")
				ArgumentCaptor<Consumer<CatalogPushResult>> callback = ArgumentCaptor.forClass(Consumer.class);
				command(" CATALOG ", " SYNC ");
				message(ChatColor.YELLOW, "Syncing drink catalog + assets…");
				catalog.verify(() -> CatalogSyncService.pushAsync(eq(plugin), callback.capture()));
				assets.verifyNoInteractions();
				callback.getValue().accept(result);
				assets.verify(() -> AssetSyncService.pushAsync(plugin));
				if (result.ok) {
					message(ChatColor.GREEN, "Catalog synced: 4 ingredients. Assets sync started.");
				} else {
					message(ChatColor.RED, "Catalog sync failed: offline");
				}
				catalog.clearInvocations();
				assets.clearInvocations();
				clearInvocations(sender);
			}
		}
	}

	@Test
	void reapplyTrimsIdAndReportsTheCallbackResult() {
		DrinkBuilder.plugin = mock(DrinkBuilder.class);
		try (var reapply = mockStatic(PackReapplyRunner.class)) {
			for (var result : List.of(ReapplyResult.success("Reapplied one"), ReapplyResult.fail("missing"))) {
				@SuppressWarnings("unchecked")
				ArgumentCaptor<Consumer<ReapplyResult>> callback = ArgumentCaptor.forClass(Consumer.class);
				command(" PACK ", " REAPPLY ", " one ");
				message(ChatColor.YELLOW, "Reapplying drink one…");
				reapply.verify(() -> PackReapplyRunner.run(eq("one"), callback.capture()));
				callback.getValue().accept(result);
				message(result.ok ? ChatColor.GREEN : ChatColor.RED, result.message);
				reapply.clearInvocations();
				clearInvocations(sender);
			}
		}
	}

	@Test
	void pullRejectsAnAlreadyRunningJob() {
		try (var pull = mockStatic(PackPullRunner.class)) {
			pull.when(PackPullRunner::isRunning).thenReturn(true);
			command("pack", "pull");
			message(ChatColor.YELLOW, "Pack pull already running.");
			pull.verify(() -> PackPullRunner.run(anyBoolean(), any()), never());
		}
	}

	@Test
	void pullPassesForceOptionAndReportsCompletionOrBusyCallback() {
		try (var pull = mockStatic(PackPullRunner.class)) {
			pull.when(() -> PackPullRunner.run(anyBoolean(), any())).thenReturn(true);
			for (String[] args : List.of(new String[]{"pack", "pull"}, new String[]{"pack", "pull", "other"},
				new String[]{"pack", "pull", " FORCE "})) {
				boolean force = args.length == 3 && args[2].trim().equals("FORCE");
				@SuppressWarnings("unchecked")
				ArgumentCaptor<Consumer<PullResult>> callback = ArgumentCaptor.forClass(Consumer.class);
				command(args);
				message(ChatColor.YELLOW, "Pulling pending drinks" + (force ? " (force IA reload)…" : "…"));
				pull.verify(() -> PackPullRunner.run(eq(force), callback.capture()));
				callback.getValue().accept(PullResult.of(1, 0, 1, 0, "one applied"));
				message(ChatColor.GREEN, "Pack pull done: one applied");
				pull.clearInvocations();
				clearInvocations(sender);
			}
			@SuppressWarnings("unchecked")
			ArgumentCaptor<Consumer<PullResult>> callback = ArgumentCaptor.forClass(Consumer.class);
			pull.when(() -> PackPullRunner.run(anyBoolean(), any())).thenReturn(false);
			command("pack", "pull");
			message(ChatColor.YELLOW, "Pack pull already running.");
			pull.verify(() -> PackPullRunner.run(eq(false), callback.capture()));
			callback.getValue().accept(PullResult.skippedBusy());
			message(ChatColor.YELLOW, "pack pull already running");
		}
	}

	@Test
	void deleteRunsInBackgroundAndSendsResultOnMainThread() {
		var plugin = mock(DrinkBuilder.class);
		DrinkBuilder.plugin = plugin;
		var scheduler = mock(BukkitScheduler.class);
		try (var bukkit = mockStatic(Bukkit.class); var delete = mockStatic(DrinkDeleteRunner.class)) {
			bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
			delete.when(() -> DrinkDeleteRunner.run("one")).thenReturn("Deleted drink one");
			command(" DRINK ", " DELETE ", " one ");
			message(ChatColor.YELLOW, "Deleting drink one…");
			var background = ArgumentCaptor.forClass(Runnable.class);
			verify(scheduler).runTaskAsynchronously(eq(plugin), background.capture());
			delete.verifyNoInteractions();
			background.getValue().run();
			delete.verify(() -> DrinkDeleteRunner.run("one"));
			verify(sender, never()).sendMessage(ChatColor.GREEN + "Deleted drink one");
			var foreground = ArgumentCaptor.forClass(Runnable.class);
			verify(scheduler).runTask(eq(plugin), foreground.capture());
			foreground.getValue().run();
			message(ChatColor.GREEN, "Deleted drink one");
		}
	}

	@Test
	void deleteReportsPartialCleanupAsAnError() {
		var plugin = mock(DrinkBuilder.class);
		DrinkBuilder.plugin = plugin;
		var scheduler = mock(BukkitScheduler.class);
		try (var bukkit = mockStatic(Bukkit.class); var delete = mockStatic(DrinkDeleteRunner.class)) {
			bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
			when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(inv -> {
				inv.getArgument(1, Runnable.class).run(); return null;
			});
			when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(inv -> {
				inv.getArgument(1, Runnable.class).run(); return null;
			});
			delete.when(() -> DrinkDeleteRunner.run("one")).thenReturn("Drink one revoked, but IA cleanup failed: disk");
			command("drink", "delete", "one");
			message(ChatColor.RED, "Drink one revoked, but IA cleanup failed: disk");
		}
	}

	@Test
	void completionRequiresPermissionForBothPlayersAndConsole() {
		when(sender.hasPermission(Permissions.ADMIN)).thenReturn(false);
		assertTrue(complete("").isEmpty());
		var player = mock(Player.class);
		assertTrue(manager.onTabComplete(player, null, "drinkbuilder", new String[]{""}).isEmpty());
		when(player.hasPermission(Permissions.ADMIN)).thenReturn(true);
		assertEquals(List.of("reload"), manager.onTabComplete(player, null, "drinkbuilder", new String[]{"RE"}));
	}

	@Test
	void completionFiltersCommandNamesAndActionsCaseInsensitively() {
		assertEquals(List.of("reload", "catalog", "pack", "drink"), complete(""));
		assertEquals(List.of("reload"), complete("RE"));
		assertEquals(List.of("catalog"), complete("CA"));
		assertEquals(List.of("pack"), complete("PA"));
		assertEquals(List.of("drink"), complete("DR"));
		assertEquals(List.of(), complete("unknown"));
		assertEquals(List.of("sync"), complete("CATALOG", "S"));
		assertEquals(List.of(), complete("catalog", "other"));
		assertEquals(List.of("pull"), complete("PACK", "P"));
		assertEquals(List.of("reapply"), complete("pack", "R"));
		assertEquals(List.of(), complete("pack", "other"));
		assertEquals(List.of("delete"), complete("DRINK", "D"));
		assertEquals(List.of(), complete("drink", "other"));
		assertEquals(List.of("force"), complete("PACK", "PULL", "F"));
		assertEquals(List.of(), complete("pack", "pull", "other"));
		assertEquals(List.of(), complete("pack", "other", ""));
		assertEquals(List.of(), complete("drink", "other", ""));
		assertEquals(List.of(), complete("unknown", "", ""));
		assertEquals(List.of(), complete("unknown", ""));
		assertEquals(List.of(), complete());
		assertEquals(List.of(), complete("pack", "pull", "force", "extra"));
	}

	@Test
	void completionFiltersCachedDrinkIdsAndPreservesTheirCase() {
		try (var cache = mockStatic(DeletableDrinkCache.class)) {
			cache.when(DeletableDrinkCache::snapshot).thenReturn(List.of("Ale", "ale-two", "Beer"));
			assertEquals(List.of("Ale", "ale-two"), complete("PACK", "REAPPLY", "AL"));
			assertEquals(List.of("Beer"), complete("DRINK", "DELETE", "B"));
			assertEquals(List.of(), complete("drink", "delete", "missing"));
			assertEquals(List.of(), complete("pack", "reapply", "missing"));
			cache.when(DeletableDrinkCache::snapshot).thenReturn(List.of());
			assertEquals(List.of(), complete("drink", "delete", ""));
		}
	}

	private void command(String... args) {
		assertTrue(manager.onCommand(sender, null, "drinkbuilder", args));
	}

	private List<String> complete(String... args) {
		return manager.onTabComplete(sender, null, "drinkbuilder", args);
	}

	private void message(ChatColor colour, String text) {
		verify(sender).sendMessage(colour + text);
	}
}
