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

## Documentation

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/DrinkBuilder/README.md)

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs).

[Failure recovery and troubleshooting](https://github.com/TF-Minecraft/Docs/blob/main/projects/DrinkBuilder/troubleshooting.md)

## Tests and coverage

Run `mvn clean verify` with Java 21 and the pinned plugin dependencies installed
(the build workflow prepares them). The suite uses JUnit 5 and Mockito, with
temporary files, mocked server/API boundaries and test-only optional-plugin
fixtures; it does not boot a Minecraft server.

JaCoCo writes HTML and XML reports to `target/site/jacoco/`, and CI uploads them
as a `coverage-report-*` artifact. The Maven gate fails verification if more than
6 lines or 32 branches are missed, or if any method or class is missed.

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and bundled material
retain their own licenses.
