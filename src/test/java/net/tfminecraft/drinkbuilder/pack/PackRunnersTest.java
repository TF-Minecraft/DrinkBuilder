package net.tfminecraft.drinkbuilder.pack;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.tfminecraft.drinkbuilder.DrinkBuilder;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class PackRunnersTest {
    static PendingDrink drink(String id, String status, String label, String texture) {
        return new PendingDrink(id, null, null, label, status, false, texture, Map.of(), List.of(), null);
    }

    static final class Fixture implements AutoCloseable {
        final DrinkBuilder plugin = mock(DrinkBuilder.class);
        final Logger log = mock(Logger.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final CmdAllocator allocator = mock(CmdAllocator.class);
        final DeferredDrinkIaReload reload = mock(DeferredDrinkIaReload.class);
        final PendingReloadQueue queue = mock(PendingReloadQueue.class);
        final MockedStatic<JavaPlugin> plugins = mockStatic(JavaPlugin.class);
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedStatic<ProvinceSystemClient> api = mockStatic(ProvinceSystemClient.class);
        final MockedStatic<RecipesYmlMerger> recipes = mockStatic(RecipesYmlMerger.class);
        final MockedStatic<IaDrinksWriter> writer = mockStatic(IaDrinksWriter.class);
        final MockedStatic<IaDrinksRemover> remover = mockStatic(IaDrinksRemover.class);
        final MockedStatic<DeletableDrinkCache> cache = mockStatic(DeletableDrinkCache.class);
        Fixture() {
            plugins.when(() -> JavaPlugin.getPlugin(DrinkBuilder.class)).thenReturn(plugin);
            when(plugin.getLogger()).thenReturn(log);
            when(plugin.getCmdAllocator()).thenReturn(allocator);
            when(plugin.getDeferredIaReload()).thenReturn(reload);
            when(reload.queue()).thenReturn(queue);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(() -> Bukkit.dispatchCommand(any(), anyString())).thenReturn(true);
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(inv -> {
                inv.getArgument(1, Runnable.class).run(); return null;
            });
            when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(inv -> {
                inv.getArgument(1, Runnable.class).run(); return null;
            });
        }
        public void close() {
            cache.close(); remover.close(); writer.close(); recipes.close(); api.close(); bukkit.close(); plugins.close();
        }
    }

    @Test void applyUsesExistingCmdOrNewlyWrittenCmd() throws Exception {
        try (Fixture f = new Fixture()) {
            PendingDrink existing = new PendingDrink("old", null, null, null, "approved", false, "tex", null, null,
                new TextureInfo("tex", 123, "drinks:old", null));
            assertFalse(PackPullRunner.applyDrink(f.plugin, existing, f.allocator, f.log));
            f.recipes.verify(() -> RecipesYmlMerger.merge(f.plugin, existing, 123, f.log));
            PendingDrink fresh = drink("new", "approved", null, "tex");
            f.writer.when(() -> IaDrinksWriter.write(f.plugin, fresh, f.allocator, f.log))
                .thenReturn(new IaDrinksWriter.WriteResult(456, "drinks:new"));
            assertTrue(PackPullRunner.applyDrink(f.plugin, fresh, f.allocator, f.log));
            f.recipes.verify(() -> RecipesYmlMerger.merge(f.plugin, fresh, 456, f.log));
        }
    }

    @Test void pullSeparatesImmediateAndIaAcksAndContinuesAfterIndividualFailure() throws Exception {
        try (Fixture f = new Fixture()) {
            PendingDrink plain = drink(" plain ", "approved", null, null);
            PendingDrink textured = drink(" textured ", "approved", null, "tex");
            PendingDrink broken = drink("broken", "approved", null, null);
            f.api.when(ProvinceSystemClient::listPendingApply).thenReturn(ListResult.success(List.of(plain, textured, broken)));
            f.writer.when(() -> IaDrinksWriter.write(f.plugin, textured, f.allocator, f.log))
                .thenReturn(new IaDrinksWriter.WriteResult(456, "drinks:textured"));
            f.recipes.when(() -> RecipesYmlMerger.merge(f.plugin, broken, null, f.log)).thenThrow(new IOException("disk full"));
            f.api.when(() -> ProvinceSystemClient.markApplied(List.of("plain"))).thenReturn(AppliedResult.success(List.of("plain")));
            AtomicReference<PackPullRunner.PullResult> result = new AtomicReference<>();
            assertTrue(PackPullRunner.run(true, result::set));
            assertFalse(PackPullRunner.isRunning());
            assertTrue(result.get().ok);
            assertFalse(result.get().busy);
            assertEquals(2, result.get().written);
            assertEquals(1, result.get().failed);
            assertEquals(1, result.get().ackNow);
            assertEquals(1, result.get().queuedIa);
            assertTrue(result.get().summary.contains("broken: disk full"));
            verify(f.queue).enqueue(List.of("textured"));
            verify(f.reload).requestFlush(true);
            f.bukkit.verify(() -> Bukkit.dispatchCommand(null, "brew reload"));
        }
    }

    @Test void textureAwaitingZipIsNotAckedWhenTheNextPollSeesItsCmd() {
        try (Fixture f = new Fixture()) {
            // The first poll wrote the texture and reported its CMD; the zip waits for an empty server.
            PendingDrink written = new PendingDrink("brew", null, null, null, "approved", false, "tex", Map.of(), List.of(),
                new TextureInfo("tex", 20003, "tfmc_drinks:brew", null));
            f.api.when(ProvinceSystemClient::listPendingApply).thenReturn(ListResult.success(List.of(written)));
            when(f.queue.snapshot()).thenReturn(List.of("brew"));
            AtomicReference<PackPullRunner.PullResult> result = new AtomicReference<>();
            PackPullRunner.run(false, result::set);
            assertEquals(0, result.get().ackNow);
            assertEquals(1, result.get().queuedIa);
            f.api.verify(() -> ProvinceSystemClient.markApplied(any()), never());
            verify(f.queue).enqueue(List.of("brew"));
        }
    }

    @Test void pullHandlesListFailureEmptyListAndThrownFailure() {
        try (Fixture f = new Fixture()) {
            f.api.when(ProvinceSystemClient::listPendingApply).thenReturn(ListResult.fail("offline"), ListResult.success(List.of()))
                .thenThrow(new IllegalStateException("network"));
            List<PackPullRunner.PullResult> results = new ArrayList<>();
            assertTrue(PackPullRunner.run(false, results::add));
            assertTrue(PackPullRunner.run(false, results::add));
            assertTrue(PackPullRunner.run(false, results::add));
            assertTrue(results.get(0).summary.contains("list failed: offline"));
            assertTrue(results.get(1).summary.contains("no pending drinks"));
            assertEquals(1, results.get(2).failed);
            assertTrue(results.get(2).summary.contains("network"));
            verify(f.reload, times(3)).requestFlush(false);
            verify(f.queue, never()).enqueue(any());
            f.bukkit.verify(() -> Bukkit.dispatchCommand(any(), anyString()), never());
        }
    }

    @Test void busyPullIsRejectedWithOptionalCallbackAndLockReleasedAfterCallbackFailure() {
        try (Fixture f = new Fixture()) {
            List<Runnable> jobs = new ArrayList<>();
            when(f.scheduler.runTaskAsynchronously(eq(f.plugin), any(Runnable.class))).thenAnswer(inv -> {
                jobs.add(inv.getArgument(1)); return null;
            });
            f.api.when(ProvinceSystemClient::listPendingApply).thenReturn(ListResult.success(List.of()));
            assertTrue(PackPullRunner.run(false, result -> { throw new IllegalStateException("callback"); }));
            assertTrue(PackPullRunner.isRunning());
            AtomicReference<PackPullRunner.PullResult> busy = new AtomicReference<>();
            assertFalse(PackPullRunner.run(false, busy::set));
            assertFalse(PackPullRunner.run(false, null));
            assertTrue(busy.get().busy);
            assertFalse(busy.get().ok);
            assertEquals("pack pull already running", busy.get().summary);
            assertThrows(IllegalStateException.class, () -> jobs.remove(0).run());
            assertFalse(PackPullRunner.isRunning());
        }
    }

    @Test void pullReportsFailedAndPartialAcksAndMissingReloadService() {
        try (Fixture f = new Fixture()) {
            PendingDrink plain = drink("plain", "approved", null, null);
            f.api.when(ProvinceSystemClient::listPendingApply).thenReturn(ListResult.success(List.of(plain)));
            f.api.when(() -> ProvinceSystemClient.markApplied(List.of("plain")))
                .thenReturn(AppliedResult.fail("offline"), AppliedResult.success(List.of()));
            f.bukkit.when(() -> Bukkit.dispatchCommand(any(), anyString())).thenReturn(false);
            assertTrue(PackPullRunner.run(false, null));
            assertTrue(PackPullRunner.run(false, null));
            verify(f.log).warning("[pack] immediate applied ack failed: offline");
            verify(f.log).warning(contains("applied ack partial:"));
            when(f.plugin.getDeferredIaReload()).thenReturn(null);
            assertTrue(PackPullRunner.run(false, null));
        }
    }

    @Test void reapplyValidatesInputBusyFetchAndStatus() {
        try (Fixture f = new Fixture(); MockedStatic<PackPullRunner> pull = mockStatic(PackPullRunner.class)) {
            AtomicReference<PackReapplyRunner.ReapplyResult> result = new AtomicReference<>();
            PackReapplyRunner.run(null, result::set);
            assertEquals("submission id required", result.get().message);
            PackReapplyRunner.run(" ", null);
            pull.when(PackPullRunner::isRunning).thenReturn(true);
            PackReapplyRunner.run("x", result::set);
            assertEquals("pack pull already running", result.get().message);
            pull.when(PackPullRunner::isRunning).thenReturn(false);
            f.api.when(() -> ProvinceSystemClient.getDrink("x"))
                .thenReturn(DrinkGetResult.fail("offline"), DrinkGetResult.success(null),
                    DrinkGetResult.success(drink("x", null, null, null)),
                    DrinkGetResult.success(drink("x", "rejected", null, null)));
            PackReapplyRunner.run(" x ", result::set);
            assertEquals("offline", result.get().message);
            PackReapplyRunner.run("x", result::set);
            assertEquals("drink not found", result.get().message);
            PackReapplyRunner.run("x", result::set);
            assertFalse(result.get().ok);
            PackReapplyRunner.run("x", result::set);
            assertTrue(result.get().message.contains("rejected"));
        }
    }

    @Test void reapplyAcceptedStatusesReloadAndFailure() throws Exception {
        try (Fixture f = new Fixture(); MockedStatic<PackPullRunner> pull = mockStatic(PackPullRunner.class)) {
            AtomicReference<PackReapplyRunner.ReapplyResult> result = new AtomicReference<>();
            for (String status : List.of(" APPROVED ", "pending_pack", "applied")) {
                PendingDrink drink = drink("x", status, null, null);
                f.api.when(() -> ProvinceSystemClient.getDrink("x")).thenReturn(DrinkGetResult.success(drink));
                pull.when(() -> PackPullRunner.applyDrink(f.plugin, drink, f.allocator, f.log)).thenReturn(false);
                PackReapplyRunner.run("x", result::set);
                assertTrue(result.get().ok);
                assertEquals("Reapplied x (brewery only)", result.get().message);
            }
            PendingDrink drink = drink("x", "approved", null, "tex");
            f.api.when(() -> ProvinceSystemClient.getDrink("x")).thenReturn(DrinkGetResult.success(drink));
            pull.when(() -> PackPullRunner.applyDrink(f.plugin, drink, f.allocator, f.log)).thenReturn(true);
            f.bukkit.when(() -> Bukkit.dispatchCommand(any(), anyString())).thenReturn(false);
            PackReapplyRunner.run(" x ", result::set);
            assertEquals("Reapplied x (IA refresh queued)", result.get().message);
            verify(f.queue).enqueue(List.of("x"));
            verify(f.reload).requestFlush(true);
            when(f.plugin.getDeferredIaReload()).thenReturn(null);
            PackReapplyRunner.run("x", result::set);
            assertTrue(result.get().ok);
            pull.when(() -> PackPullRunner.applyDrink(f.plugin, drink, f.allocator, f.log)).thenThrow(new IOException("write failed"));
            PackReapplyRunner.run("x", result::set);
            assertFalse(result.get().ok);
            assertEquals("write failed", result.get().message);
        }
    }

    @Test void deleteValidatesInputFetchAndStatusBeforeTouchingFiles() {
        try (Fixture f = new Fixture()) {
            assertEquals("Drink id is required.", DrinkDeleteRunner.run(null));
            assertEquals("Drink id is required.", DrinkDeleteRunner.run(" "));
            f.api.when(() -> ProvinceSystemClient.getDrink("x"))
                .thenReturn(DrinkGetResult.fail("offline"), DrinkGetResult.success(null),
                    DrinkGetResult.success(drink("x", null, null, null)),
                    DrinkGetResult.success(drink("x", "draft", null, null)));
            assertEquals("offline", DrinkDeleteRunner.run("x"));
            assertEquals("Could not load drink.", DrinkDeleteRunner.run("x"));
            assertTrue(DrinkDeleteRunner.run("x").contains("status=null"));
            assertTrue(DrinkDeleteRunner.run("x").contains("status=draft"));
            f.recipes.verifyNoInteractions();
        }
    }

    @Test void deleteStopsBeforeRevocationWhenRecipeCleanupFails() throws Exception {
        try (Fixture f = new Fixture()) {
            f.api.when(() -> ProvinceSystemClient.getDrink("x")).thenReturn(DrinkGetResult.success(drink("x", "applied", null, null)));
            f.recipes.when(() -> RecipesYmlMerger.remove(f.plugin, "x", f.log)).thenThrow(new IOException("disk"));
            assertEquals("Could not delete drink x: recipe cleanup failed: disk. Website record retained.", DrinkDeleteRunner.run("x"));
            f.api.verify(() -> ProvinceSystemClient.revokeDrink(anyString()), never());
            verifyNoInteractions(f.allocator, f.reload);
            f.bukkit.verify(() -> Bukkit.dispatchCommand(any(), anyString()), never());
        }
    }

    @Test void failedTextureCleanupNeverFreesTheModelIdOrClaimsSuccess() throws Exception {
        try (Fixture f = new Fixture()) {
            f.api.when(() -> ProvinceSystemClient.getDrink("x")).thenReturn(DrinkGetResult.success(drink("x", "applied", "Cider", "tex")));
            f.api.when(() -> ProvinceSystemClient.revokeDrink("x")).thenReturn(RevokeResult.success(true, "tex", "tfmc_drinks:x", 123));
            f.remover.when(() -> IaDrinksRemover.remove(f.plugin, "tfmc_drinks:x", f.log)).thenThrow(new IOException("disk"));
            String result = DrinkDeleteRunner.run("x");
            assertTrue(result.contains("IA cleanup failed: disk"));
            assertFalse(result.contains("Texture + CMD freed"));
            verify(f.allocator, never()).free(anyInt());
            f.cache.verify(DeletableDrinkCache::invalidate);
        }
    }

    @Test void deleteReportsApiFailureAfterSuccessfulLocalCleanup() {
        try (Fixture f = new Fixture()) {
            f.api.when(() -> ProvinceSystemClient.getDrink("x")).thenReturn(DrinkGetResult.success(drink("x", "applied", null, null)));
            f.bukkit.when(() -> Bukkit.dispatchCommand(any(), anyString())).thenReturn(false);
            f.api.when(() -> ProvinceSystemClient.revokeDrink("x")).thenReturn(RevokeResult.fail("offline"), RevokeResult.fail(null));
            assertEquals("Local recipe cleanup done but API revoke failed: offline", DrinkDeleteRunner.run(" x "));
            assertEquals("Local recipe cleanup done but API revoke failed: unknown", DrinkDeleteRunner.run("x"));
            f.cache.verifyNoInteractions();
        }
    }

    @Test void deleteFreesTextureAndCmdAndRequestsForcedRefresh() throws Exception {
        try (Fixture f = new Fixture()) {
            f.api.when(() -> ProvinceSystemClient.getDrink("x")).thenReturn(DrinkGetResult.success(drink("x", "pending_pack", "Cider", "tex")));
            f.api.when(() -> ProvinceSystemClient.revokeDrink("x")).thenReturn(RevokeResult.success(true, "tex", "drinks:x", 123));
            f.remover.when(() -> IaDrinksRemover.remove(f.plugin, "drinks:x", f.log)).thenReturn(true);
            assertEquals("Deleted drink x (Cider). Texture + CMD freed.", DrinkDeleteRunner.run("x"));
            verify(f.allocator).free(123);
            verify(f.reload).requestRefresh();
            verify(f.queue).clear(List.of("x"));
            f.cache.verify(DeletableDrinkCache::invalidate);
            when(f.plugin.getDeferredIaReload()).thenReturn(null);
            DrinkDeleteRunner.run("x");
            f.remover.when(() -> IaDrinksRemover.remove(f.plugin, "drinks:x", f.log)).thenThrow(new IOException("disk"));
            f.api.when(() -> ProvinceSystemClient.revokeDrink("x")).thenReturn(RevokeResult.success(true, "tex", "drinks:x", null));
            assertTrue(DrinkDeleteRunner.run("x").contains("IA cleanup failed: disk"));
            verify(f.log).warning("[drink-delete] IA remove failed; CMD retained: disk");
            f.remover.when(() -> IaDrinksRemover.remove(f.plugin, "drinks:x", f.log)).thenReturn(false);
            assertTrue(DrinkDeleteRunner.run("x").contains("Texture + CMD freed"));
        }
    }

    @Test void deleteKeepsSharedTexturesAndFallsBackToIdForMissingLabels() {
        try (Fixture f = new Fixture()) {
            f.api.when(() -> ProvinceSystemClient.revokeDrink("x")).thenReturn(RevokeResult.success(false, null, null, null));
            for (String texture : new String[] {null, "", "tex"}) {
                for (String label : new String[] {null, "", "Cider"}) {
                    f.api.when(() -> ProvinceSystemClient.getDrink("x"))
                        .thenReturn(DrinkGetResult.success(drink("x", "applied", label, texture)));
                    assertEquals("Deleted drink x (" + (label == null || label.isBlank() ? "x" : label) + ")."
                        + ("tex".equals(texture) ? " Shared texture kept." : ""), DrinkDeleteRunner.run("x"));
                }
            }
            f.remover.verifyNoInteractions();
            verifyNoInteractions(f.allocator);
            verify(f.reload, never()).requestRefresh();
        }
    }

    @Test void deleteRetainsCmdWhenItemIdentityIsMissing() {
        try (Fixture f = new Fixture()) {
            f.api.when(() -> ProvinceSystemClient.getDrink("x")).thenReturn(DrinkGetResult.success(drink("x", "applied", "Cider", "tex")));
            for (String iaId : new String[]{null, " "}) {
                f.api.when(() -> ProvinceSystemClient.revokeDrink("x")).thenReturn(RevokeResult.success(true, "tex", iaId, 123));
                assertTrue(DrinkDeleteRunner.run("x").contains("missing ItemsAdder item id"));
            }
            verify(f.allocator, never()).free(anyInt());
            f.remover.verifyNoInteractions();
            f.api.when(() -> ProvinceSystemClient.revokeDrink("x")).thenReturn(RevokeResult.success(true, "tex", null, null));
            assertTrue(DrinkDeleteRunner.run("x").startsWith("Deleted drink x"));
        }
    }

    @Test void pendingLocalWriteIsRepairedEvenWhenWebsiteAlreadyHasCmd() throws Exception {
        try (Fixture f = new Fixture()) {
            PendingDrink drink = new PendingDrink("x", null, null, null, "approved", false, "tex", null, null,
                new TextureInfo("tex", 123, "tfmc_drinks:x", null));
            f.writer.when(() -> IaDrinksWriter.hasPendingWrite(f.plugin, "x")).thenReturn(true);
            f.writer.when(() -> IaDrinksWriter.write(f.plugin, drink, f.allocator, f.log))
                .thenReturn(new IaDrinksWriter.WriteResult(123, "tfmc_drinks:x"));
            assertTrue(PackPullRunner.applyDrink(f.plugin, drink, f.allocator, f.log));
            f.recipes.verify(() -> RecipesYmlMerger.merge(f.plugin, drink, 123, f.log));
        }
    }

    @Test void deletingUnpublishedTextureCancelsItsPendingReservationAndRefreshes() throws Exception {
        try (Fixture f = new Fixture()) {
            f.api.when(() -> ProvinceSystemClient.getDrink("x")).thenReturn(DrinkGetResult.success(drink("x", "approved", "Cider", "tex")));
            f.api.when(() -> ProvinceSystemClient.revokeDrink("x")).thenReturn(RevokeResult.success(true, "tex", null, null));
            f.writer.when(() -> IaDrinksWriter.cancelPendingWrite(f.plugin, "x", f.allocator, f.log)).thenReturn(true);
            assertTrue(DrinkDeleteRunner.run("x").startsWith("Deleted drink x"));
            verify(f.reload).requestRefresh();
        }
    }

    @Test void deleteDispatchesBreweryReloadOnlyThroughTheServerScheduler() {
        try (Fixture f = new Fixture()) {
            List<Runnable> mainTasks = new ArrayList<>();
            when(f.scheduler.runTask(eq(f.plugin), any(Runnable.class))).thenAnswer(inv -> {
                mainTasks.add(inv.getArgument(1)); return null;
            });
            f.api.when(() -> ProvinceSystemClient.getDrink("x")).thenReturn(DrinkGetResult.success(drink("x", "applied", null, null)));
            f.api.when(() -> ProvinceSystemClient.revokeDrink("x")).thenReturn(RevokeResult.success(false, null, null, null));
            DrinkDeleteRunner.run("x");
            f.bukkit.verify(() -> Bukkit.dispatchCommand(any(), anyString()), never());
            assertEquals(1, mainTasks.size());
            mainTasks.getFirst().run();
            f.bukkit.verify(() -> Bukkit.dispatchCommand(null, "brew reload"));
        }
    }
}
