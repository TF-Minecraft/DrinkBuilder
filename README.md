# DrinkBuilder

> Bring custom TF-Minecraft drinks from the website into the game.

DrinkBuilder connects the website's drink creator with the server's brewing and custom-item systems. Players design drinks through the web experience, staff review submissions, and this plugin brings approved drinks into Minecraft with their recipes and visual identity.

It keeps the creator supplied with the ingredients and options the server supports, then handles the in-game side of publishing a drink. The web interface lives in [ProvinceSystem](https://github.com/TF-Minecraft/ProvinceSystem).

## Features

- **A shared ingredient catalogue** — publish available ingredients and their categories, including vanilla items and custom ingredients.
- **Approved recipe delivery** — bring accepted drink recipes into BreweryX so they can become part of the brewing experience.
- **Custom drink appearance** — publish drink names, textures, and item definitions through ItemsAdder.
- **Creator entitlements** — share which players can use name colours, custom textures, and custom drink messages.
- **Consistent previews** — send bottle and liquid artwork to the website so its drink previews use the server's assets.
- **Drink lifecycle support** — apply new drinks, reapply existing ones, and remove drinks from the connected systems.

## Failure recovery

After plugin startup and DrinkBuilder reloads, DrinkBuilder restores BreweryX's
native MMOItems and ItemsAdder hooks if an optional-dependency cycle caused
BreweryX to cache them as disabled. Recipes and cauldron ingredients are then
loaded again, including existing drinks. The `MMOItems:ID` ingredient format
is retained for both the website catalog and in-game recipes.

Legacy potion effect names are translated to their current names when publishing
drinks (for example, `CONFUSION` becomes `NAUSEA`). Existing BreweryX recipes
receive the same migration, with a `recipes-before-effect-migration-*.bak` copy
saved first. Invalid YAML is left untouched and reported in the server log.
The compatibility API has been verified against BreweryX 3.7.0 on Paper 1.21.10.
Migration, publication, and removal share a recipe write lock, so concurrent
DrinkBuilder operations cannot overwrite each other's changes. If BreweryX's
registration or live reload fails partway through recovery, unfinished steps
are retained and retried on the next DrinkBuilder reload.

Ingredient quantities from website JSON retain their exact positive integer
values when written to BreweryX. Invalid, fractional, or missing quantities fail
the drink apply before texture publication or replacement of its existing recipe.

The bundled ingredient allowlist omits retired herbs with no active acquisition
source, even when MMOItems still defines them. Existing `ingredients.yml` files
are preserved; operators should prune any retired entries there and sync the
catalog. This does not replace ingredients in previously approved drinks; those
recipes need their creators to choose available replacements.

Failed texture publication restores the previous local files. If the website may
have accepted the model ID, `pending-writes/` retains that reservation so the next
pull retries with the same ID, including after a restart. Do not delete those
records or reset `cmd-state.yml` to work around an error; doing so can reuse an ID
that is already assigned. Damaged allocator state now stops initialization instead
of silently restarting allocation from the beginning of the range.

If deletion reports that local cleanup failed, the model ID remains reserved.
The message and server log identify the failed cleanup; successful website
revocation is not reported as successful local cleanup.

## Tests and coverage

Run `mvn clean verify` with Java 21 and the pinned plugin dependencies installed
(the build workflow prepares them). JaCoCo writes HTML and XML reports to
`target/site/jacoco/`; CI uploads the report as a `coverage-report` artifact.

The suite currently has 164 passing tests: **99.74% line coverage, 97.78% branch
coverage, and 100% method/class coverage**, with no production-code exclusions.
The Maven gate permits at most 6 missed lines and 32 missed branches, and no
missed methods or classes, so additional uncovered code fails verification.

The six remaining lines are defensive fallbacks in `CatalogSyncService.escape`,
`IngredientExistenceChecker.vanillaExists`, `ProvinceSystemClient.putBytes`,
`DeferredDrinkIaReload.ackQueued`, and `ConfigLoader.load`. Most remaining
branches likewise guard values already normalized by their callers; the report
retains these gaps. Tests use temporary files and mocked server/API boundaries,
including test-only optional-plugin fixtures; they do not boot a Minecraft server.

## Documentation

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/DrinkBuilder/README.md)

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs).

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and bundled material
retain their own licenses.
