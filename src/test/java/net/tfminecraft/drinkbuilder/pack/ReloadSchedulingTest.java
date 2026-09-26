package net.tfminecraft.drinkbuilder.pack;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.tfminecraft.drinkbuilder.Cache;
import net.tfminecraft.drinkbuilder.DrinkBuilder;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class ReloadSchedulingTest {
    private final int oldPoll = Cache.packPollIntervalSeconds;
    private final String oldTime = Cache.forceReloadTime;
    private final int oldDelay = Cache.iaReloadDelaySeconds;

    @AfterEach void restoreConfiguration() {
        Cache.packPollIntervalSeconds = oldPoll;
        Cache.forceReloadTime = oldTime;
        Cache.iaReloadDelaySeconds = oldDelay;
    }

    static final class Fixture implements AutoCloseable {
        final DrinkBuilder plugin = mock(DrinkBuilder.class);
        final Logger log = mock(Logger.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final PluginManager manager = mock(PluginManager.class);
        final PendingReloadQueue pending = mock(PendingReloadQueue.class);
        final Queue<Runnable> sync = new ArrayDeque<>();
        final Queue<Runnable> async = new ArrayDeque<>();
        final Queue<Runnable> later = new ArrayDeque<>();
        final List<Runnable> timers = new ArrayList<>();
        final List<BukkitTask> tasks = new ArrayList<>();
        final List<BukkitTask> delayedTasks = new ArrayList<>();
        final MockedStatic<JavaPlugin> plugins = mockStatic(JavaPlugin.class);
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedStatic<ProvinceSystemClient> api = mockStatic(ProvinceSystemClient.class);
        Fixture() {
            plugins.when(() -> JavaPlugin.getPlugin(DrinkBuilder.class)).thenReturn(plugin);
            when(plugin.getLogger()).thenReturn(log);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            bukkit.when(() -> Bukkit.dispatchCommand(any(), anyString())).thenReturn(true);
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(inv -> {
                sync.add(inv.getArgument(1)); return mock(BukkitTask.class);
            });
            when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(inv -> {
                async.add(inv.getArgument(1)); return mock(BukkitTask.class);
            });
            when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong())).thenAnswer(inv -> {
                later.add(inv.getArgument(1));
                BukkitTask task = mock(BukkitTask.class); delayedTasks.add(task); return task;
            });
            when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenAnswer(inv -> {
                timers.add(inv.getArgument(1)); BukkitTask task = mock(BukkitTask.class); tasks.add(task); return task;
            });
            when(pending.snapshot()).thenReturn(List.of("one", "two"));
            when(pending.size()).thenReturn(2);
        }
        void drain() {
            while (!sync.isEmpty() || !async.isEmpty()) {
                while (!sync.isEmpty()) sync.remove().run();
                while (!async.isEmpty()) async.remove().run();
            }
        }
        public void close() { api.close(); bukkit.close(); plugins.close(); }
    }

    @Test void schedulerClampsPollCancelsOldTasksAndHandlesDisabledConfiguration() {
        try (Fixture f = new Fixture(); MockedStatic<PackPullRunner> pull = mockStatic(PackPullRunner.class)) {
            PackPullScheduler scheduler = new PackPullScheduler(f.plugin);
            Cache.packPollIntervalSeconds = 1;
            Cache.forceReloadTime = " 12:34 ";
            scheduler.start();
            verify(f.scheduler).runTaskTimer(eq(f.plugin), any(Runnable.class), eq(600L), eq(600L));
            f.timers.get(0).run();
            pull.verify(() -> PackPullRunner.run(false, null));
            Cache.packPollIntervalSeconds = 900;
            scheduler.start();
            verify(f.tasks.get(0)).cancel();
            verify(f.tasks.get(1)).cancel();
            verify(f.scheduler).runTaskTimer(eq(f.plugin), any(Runnable.class), eq(600L), eq(12000L));
            Cache.packPollIntervalSeconds = 0;
            Cache.forceReloadTime = null;
            scheduler.start();
            Cache.forceReloadTime = " ";
            scheduler.start();
            scheduler.stop();
            scheduler.stop();
            for (BukkitTask task : f.tasks) verify(task).cancel();
            verify(f.log, times(2)).info("[pack] force-reload-time disabled");
        }
    }

    @Test void dailyPullRequiresMatchingMinuteAndRunsOnlyOncePerDate() {
        try (Fixture f = new Fixture(); MockedStatic<PackPullRunner> pull = mockStatic(PackPullRunner.class)) {
            Cache.packPollIntervalSeconds = 0;
            Cache.forceReloadTime = null;
            PackPullScheduler scheduler = new PackPullScheduler(f.plugin);
            scheduler.start();
            Runnable tick = f.timers.get(0);
            tick.run();
            Cache.forceReloadTime = " "; tick.run();
            Cache.forceReloadTime = "bad"; tick.run();
            LocalDate today = LocalDate.of(2026, 1, 2);
            LocalTime now = LocalTime.of(12, 34);
            try (MockedStatic<LocalDate> dates = mockStatic(LocalDate.class, CALLS_REAL_METHODS);
                 MockedStatic<LocalTime> times = mockStatic(LocalTime.class, CALLS_REAL_METHODS)) {
                dates.when(LocalDate::now).thenReturn(today);
                times.when(LocalTime::now).thenReturn(now);
                Cache.forceReloadTime = "11:34"; tick.run();
                Cache.forceReloadTime = "12:33"; tick.run();
                pull.verifyNoInteractions();
                Cache.forceReloadTime = "12:34";
                tick.run(); tick.run();
                pull.verify(() -> PackPullRunner.run(true, null), times(1));
                dates.when(LocalDate::now).thenReturn(today.plusDays(1));
                tick.run();
                pull.verify(() -> PackPullRunner.run(true, null), times(2));
            }
        }
    }

    @Test void reloadWaitsForEmptyServerAndCoalescesRequestsThenFallbackAcksOnlyConfirmedIds() {
        try (Fixture f = new Fixture()) {
            Cache.iaReloadDelaySeconds = -1;
            DeferredDrinkIaReload reload = new DeferredDrinkIaReload(f.plugin, f.pending);
            assertSame(f.plugin, reload.plugin());
            assertSame(f.pending, reload.queue());
            reload.onPackCompressed(); f.drain();
            when(f.pending.isEmpty()).thenReturn(true);
            reload.requestFlush(false); f.drain();
            assertTrue(f.later.isEmpty());
            when(f.pending.isEmpty()).thenReturn(false);
            f.bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(mock(Player.class)));
            reload.requestFlush(false); f.drain();
            assertTrue(f.later.isEmpty());
            reload.requestFlush(true);
            reload.requestFlush(true);
            f.drain();
            reload.requestFlush(true); f.drain();
            assertTrue(f.sync.isEmpty());
            assertEquals(1, f.later.size());
            verify(f.scheduler).runTaskLater(eq(f.plugin), any(Runnable.class), eq(0L));
            f.later.remove().run();
            verify(f.scheduler).runTaskLater(eq(f.plugin), any(Runnable.class), eq(40L));
            f.api.when(() -> ProvinceSystemClient.markApplied(List.of("one", "two")))
                .thenReturn(AppliedResult.success(List.of("one")));
            f.later.remove().run(); f.drain();
            verify(f.pending).clear(List.of("one"));
            f.bukkit.verify(() -> Bukkit.dispatchCommand(null, "iareload"), times(1));
            f.bukkit.verify(() -> Bukkit.dispatchCommand(null, "iazip"), times(1));
        }
    }

    @Test void reloadHandlesCommandFailuresRetriesAndFailedAck() {
        try (Fixture f = new Fixture()) {
            DeferredDrinkIaReload reload = new DeferredDrinkIaReload(f.plugin, f.pending);
            f.bukkit.when(() -> Bukkit.dispatchCommand(null, "iareload")).thenReturn(false, true);
            f.bukkit.when(() -> Bukkit.dispatchCommand(null, "iazip")).thenReturn(false, true);
            reload.requestFlush(false); f.drain();
            assertTrue(f.later.isEmpty());
            verify(f.log).warning("[ia-reload] failed to dispatch iareload: will retry later");
            reload.requestFlush(false); f.drain();
            f.later.remove().run();
            verify(f.log).severe(contains("failed to dispatch iazip"));
            reload.onPackCompressed(); f.drain();
            assertTrue(f.async.isEmpty());
            reload.requestFlush(false); f.drain();
            f.later.remove().run();
            f.api.when(() -> ProvinceSystemClient.markApplied(List.of("one", "two"))).thenReturn(AppliedResult.fail("offline"));
            f.later.remove().run(); f.drain();
            verify(f.pending, never()).clear(any());
            verify(f.log).warning("[ia-reload] applied ack failed: offline");
            reload.requestFlush(false); f.drain();
            assertEquals(1, f.later.size());
        }
    }

    @Test void itemsAdderEventCompletesReloadAndNoFallbackIsScheduled() {
        try (Fixture f = new Fixture()) {
            when(f.manager.getPlugin("ItemsAdder")).thenReturn(mock(Plugin.class));
            DeferredDrinkIaReload reload = new DeferredDrinkIaReload(f.plugin, f.pending);
            reload.requestFlush(false); f.drain(); f.later.remove().run();
            assertTrue(f.later.isEmpty());
            f.api.when(() -> ProvinceSystemClient.markApplied(List.of("one", "two")))
                .thenReturn(AppliedResult.success(List.of("one", "two")));
            new ItemsAdderPackListener(reload).onPackCompressed(null);
            f.drain();
            verify(f.pending).clear(List.of("one", "two"));
            reload.onPackCompressed(); f.drain();
            assertTrue(f.async.isEmpty());
            reload.requestFlush(true); f.drain();
            when(f.pending.snapshot()).thenReturn(List.of());
            reload.onPackCompressed(); f.drain();
            assertTrue(f.async.isEmpty());
        }
    }

    @Test void newReloadCancelsOutstandingZipAndFallbackTasksAfterEarlyCompletion() {
        try (Fixture f = new Fixture()) {
            DeferredDrinkIaReload reload = new DeferredDrinkIaReload(f.plugin, f.pending);
            when(f.pending.snapshot()).thenReturn(List.of());
            reload.requestFlush(true); f.drain();
            reload.onPackCompressed(); f.drain();
            reload.requestFlush(true); f.drain();
            org.mockito.ArgumentCaptor<Runnable> actions = org.mockito.ArgumentCaptor.forClass(Runnable.class);
            verify(f.scheduler, times(2)).runTaskLater(eq(f.plugin), actions.capture(), anyLong());
            verify(f.delayedTasks.get(0)).cancel();
            // An early compression event completes the current cycle before its delayed zip fires.
            actions.getAllValues().get(1).run();
            reload.onPackCompressed(); f.drain();
            reload.requestFlush(true); f.drain();
            verify(f.scheduler, times(4)).runTaskLater(eq(f.plugin), any(Runnable.class), anyLong());
            verify(f.delayedTasks.get(2)).cancel();
        }
    }

    @Test void alreadyScheduledFallbackSafelyHandlesAnEarlyCompressionEvent() {
        try (Fixture f = new Fixture()) {
            DeferredDrinkIaReload reload = new DeferredDrinkIaReload(f.plugin, f.pending);
            reload.requestFlush(true); f.drain(); f.later.remove().run();
            when(f.pending.snapshot()).thenReturn(List.of());
            reload.onPackCompressed(); f.drain();
            // The fallback was already scheduled when another listener completed the cycle.
            f.later.remove().run();
            f.api.verifyNoInteractions();
            assertTrue(f.async.isEmpty());
        }
    }

    @Test void quittingLastPlayerTriggersPullAndListenerRegistrationIsOptional() {
        try (Fixture f = new Fixture(); MockedStatic<PackPullRunner> pull = mockStatic(PackPullRunner.class)) {
            DeferredDrinkIaReload reload = new DeferredDrinkIaReload(f.plugin, f.pending);
            f.bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(mock(Player.class)));
            reload.onPlayerQuit(null); f.drain();
            pull.verifyNoInteractions();
            f.bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            reload.onPlayerQuit(null); f.drain();
            pull.verify(() -> PackPullRunner.run(false, null));
            ItemsAdderPackListener.registerIfPresent(reload);
            verify(f.manager, never()).registerEvents(any(), any());
            when(f.manager.getPlugin("ItemsAdder")).thenReturn(mock(Plugin.class));
            ItemsAdderPackListener.registerIfPresent(reload);
            verify(f.manager).registerEvents(isA(ItemsAdderPackListener.class), eq(f.plugin));
        }
    }

    @Test void listenerSkipsRegistrationWhenPluginExistsButApiClassIsAbsent() throws Throwable {
        try (Fixture f = new Fixture()) {
            when(f.manager.getPlugin("ItemsAdder")).thenReturn(mock(Plugin.class));
            DeferredDrinkIaReload reload = new DeferredDrinkIaReload(f.plugin, f.pending);
            String bridgeName = ItemsAdderPackListener.class.getName();
            ClassLoader isolated = new ClassLoader(ItemsAdderPackListener.class.getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (name.equals("dev.lone.itemsadder.api.Events.ItemsAdderPackCompressedEvent")) {
                        throw new ClassNotFoundException(name);
                    }
                    if (!name.equals(bridgeName)) return super.loadClass(name, resolve);
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        try (var bytes = getResourceAsStream(name.replace('.', '/') + ".class")) {
                            byte[] data = bytes.readAllBytes();
                            loaded = defineClass(name, data, 0, data.length,
                                ItemsAdderPackListener.class.getProtectionDomain());
                        } catch (java.io.IOException error) {
                            throw new ClassNotFoundException(name, error);
                        }
                    }
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            };
            Class<?> bridge = isolated.loadClass(bridgeName);
            java.lang.invoke.MethodHandles.publicLookup().findStatic(bridge, "registerIfPresent",
                java.lang.invoke.MethodType.methodType(void.class, DeferredDrinkIaReload.class)).invoke(reload);
            verify(f.manager, never()).registerEvents(any(), any());
        }
    }

    @Test void cacheRefreshesOnceRetainsValuesOnFailureAndInvalidatesWithinTtl() throws Exception {
        resetCache();
        try (Fixture f = new Fixture()) {
            f.api.when(ProvinceSystemClient::listDeletableDrinks).thenReturn(
                DeletableListResult.success(List.of(new DeletableDrink("one", "Cider", "approved"))),
                DeletableListResult.fail("offline"),
                DeletableListResult.success(List.of(new DeletableDrink("two", "Mead", "applied"))));
            assertEquals(List.of(), DeletableDrinkCache.snapshot());
            assertEquals(List.of(), DeletableDrinkCache.snapshot());
            DeletableDrinkCache.invalidate();
            assertEquals(1, f.async.size());
            f.drain();
            assertEquals(List.of("one"), DeletableDrinkCache.snapshot());
            assertTrue(f.async.isEmpty());
            DeletableDrinkCache.invalidate(); f.drain();
            assertEquals(List.of("one"), DeletableDrinkCache.snapshot());
            f.drain();
            assertEquals(List.of("two"), DeletableDrinkCache.snapshot());
            f.api.verify(ProvinceSystemClient::listDeletableDrinks, times(3));
        } finally { resetCache(); }
    }

    @Test void cacheClearsInFlightAfterThrownRefresh() throws Exception {
        resetCache();
        try (Fixture f = new Fixture()) {
            f.api.when(ProvinceSystemClient::listDeletableDrinks).thenThrow(new IllegalStateException("network"))
                .thenReturn(DeletableListResult.success(List.of()));
            DeletableDrinkCache.snapshot();
            assertThrows(IllegalStateException.class, () -> f.async.remove().run());
            DeletableDrinkCache.snapshot();
            assertEquals(1, f.async.size());
            f.drain();
        } finally { resetCache(); }
    }

    @SuppressWarnings("unchecked")
    private static void resetCache() throws Exception {
        Field ids = DeletableDrinkCache.class.getDeclaredField("IDS"); ids.setAccessible(true);
        ((AtomicReference<List<String>>) ids.get(null)).set(List.of());
        Field time = DeletableDrinkCache.class.getDeclaredField("FETCHED_AT"); time.setAccessible(true);
        ((AtomicLong) time.get(null)).set(0L);
        Field running = DeletableDrinkCache.class.getDeclaredField("refreshInFlight"); running.setAccessible(true);
        running.setBoolean(null, false);
    }

    @Test void deletionRefreshBuildsPackWithoutAcknowledgingAnySubmission() {
        try (Fixture f = new Fixture()) {
            when(f.pending.isEmpty()).thenReturn(true);
            when(f.pending.size()).thenReturn(0);
            when(f.pending.snapshot()).thenReturn(List.of());
            f.bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(mock(Player.class)));
            DeferredDrinkIaReload reload = new DeferredDrinkIaReload(f.plugin, f.pending);
            reload.requestRefresh(); f.drain();
            f.bukkit.verify(() -> Bukkit.dispatchCommand(null, "iareload"));
            f.later.remove().run();
            f.bukkit.verify(() -> Bukkit.dispatchCommand(null, "iazip"));
            f.later.remove().run(); f.drain();
            f.api.verifyNoInteractions();
            verify(f.pending, never()).enqueue(any());
        }
    }

    @Test void deletionDuringExistingBuildGetsAnotherReloadAfterCompletion() {
        try (Fixture f = new Fixture()) {
            when(f.pending.isEmpty()).thenReturn(true);
            when(f.pending.snapshot()).thenReturn(List.of());
            when(f.manager.getPlugin("ItemsAdder")).thenReturn(mock(Plugin.class));
            DeferredDrinkIaReload reload = new DeferredDrinkIaReload(f.plugin, f.pending);
            reload.requestRefresh(); f.drain(); f.later.remove().run();
            reload.requestRefresh(); f.drain();
            f.bukkit.verify(() -> Bukkit.dispatchCommand(null, "iareload"), times(1));
            reload.onPackCompressed(); f.drain();
            f.bukkit.verify(() -> Bukkit.dispatchCommand(null, "iareload"), times(2));
            f.later.remove().run(); reload.onPackCompressed(); f.drain();
            f.api.verifyNoInteractions();
            assertTrue(f.later.isEmpty());
        }
    }

    @Test void failedDeletionZipRetainsRefreshIntentForNextPoll() {
        try (Fixture f = new Fixture()) {
            when(f.pending.isEmpty()).thenReturn(true);
            when(f.pending.snapshot()).thenReturn(List.of());
            f.bukkit.when(() -> Bukkit.dispatchCommand(null, "iazip")).thenReturn(false, true);
            DeferredDrinkIaReload reload = new DeferredDrinkIaReload(f.plugin, f.pending);
            reload.requestRefresh(); f.drain(); f.later.remove().run();
            assertTrue(f.later.isEmpty());
            reload.requestFlush(false); f.drain();
            f.bukkit.verify(() -> Bukkit.dispatchCommand(null, "iareload"), times(2));
            f.later.remove().run(); f.later.remove().run(); f.drain();
            f.api.verifyNoInteractions();
        }
    }

    @Test void compressionEventDefersFollowUpReloadToServerThread() {
        try (Fixture f = new Fixture()) {
            when(f.pending.isEmpty()).thenReturn(true);
            when(f.pending.snapshot()).thenReturn(List.of());
            when(f.manager.getPlugin("ItemsAdder")).thenReturn(mock(Plugin.class));
            DeferredDrinkIaReload reload = new DeferredDrinkIaReload(f.plugin, f.pending);
            reload.requestRefresh(); f.drain(); f.later.remove().run();
            reload.requestRefresh(); f.drain();
            reload.onPackCompressed();
            f.bukkit.verify(() -> Bukkit.dispatchCommand(null, "iareload"), times(1));
            assertEquals(1, f.sync.size());
            assertTrue(f.async.isEmpty());
            f.drain();
            f.bukkit.verify(() -> Bukkit.dispatchCommand(null, "iareload"), times(2));
        }
    }
}
