# Beer30 Treeify Alert

Client-only Fabric mod for Minecraft 1.21.4. When a Treeify proc turns the tree you are cutting
into bedrock, it shows `[Beer30] Treeify procced!` in chat and a pop-up above the hotbar.
It works with or without the mining script.

**Copy chat:** with chat open, hold Shift and left-click any message to copy its text to your clipboard (a green "Copied chat message" shows above the hotbar).

Needs Fabric Loader (0.16+) and Fabric API in your 1.21.4 instance. Drop the jar in `mods`.

How it works: the server sends no message on a proc, so the mod watches the blocks around you
(about 10 blocks each way, 8 down, 28 up). If 3 or more blocks that were not bedrock a moment
ago become bedrock at once, that is a proc. Knobs are the constants at the top of
`src/main/java/com/beer30/treeify/TreeifyAlert.java`.
