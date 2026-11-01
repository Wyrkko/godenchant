# God Enchants (Fabric, Minecraft 1.21.11)

Enchantments (max level 1, any sword/axe/weapon):

- **Void Rend** – each hit opens a black hole on the target for ~1.5s. Pulls everything within 8 blocks inward, 8 extra damage to each thing caught. 5s cooldown.
- **Soul Reaper** – each kill stores 2 hearts (cap 10). Right-click to consume them: heals you, overflow becomes absorption. Souls reset if you no longer carry the weapon.
- **Divine Judgment** – every 3rd hit calls lightning + a blast (radius 5) that damages enemies around the target, and you get Strength II for 5s. 8s cooldown.

Get them with:

    /enchant @s godenchants:void_rend
    /enchant @s godenchants:soul_reaper
    /enchant @s godenchants:divine_judgment

(They also show up in villager trades and loot chests.)

Build: push to GitHub, open the Actions tab, download the `GodEnchants-jar` artifact.
Requires Fabric Loader 0.18.1+ and Fabric API.
