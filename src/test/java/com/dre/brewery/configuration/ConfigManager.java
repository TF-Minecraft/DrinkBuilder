package com.dre.brewery.configuration;

/** Test-only boundary fixture; counters verify registration precedes recipe loading. */
public class ConfigManager {
    public static int registrations;
    public static int recipeLoads;
    public static int cauldronLoads;
    public static int reloads;
    public static boolean fail;
    public static boolean failConfigReload;
    public static boolean failRecipeLoad;
    public static void registerDefaultPluginItems() {
        if (fail) throw new IllegalStateException("API failure");
        registrations++;
    }
    public static void newInstance(Class<?> type, boolean overwrite) {
        if (failConfigReload) throw new IllegalStateException("Config reload failure");
        reloads++;
    }
    public static void loadRecipes() {
        if (failRecipeLoad) throw new IllegalStateException("Recipe load failure");
        recipeLoads++;
    }
    public static void loadCauldronIngredients() { cauldronLoads++; }
}
