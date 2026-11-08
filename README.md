# Pasta Client (Fabric, Minecraft Java 1.21.4)

ClickGUI: tap a module to toggle, tap its arrow for sliders, drag headers to move panels, X (top-right) closes.
Text fields along the bottom: Fake name, Skin file, Spotify token.

Render:  Player ESP, Tracers, Nametags, Entity ESP (chest minecarts)
Finders: Storage ESP, Amethyst ESP (with geode labels), Sus Chunk Finder (with chunk score labels), Block ESP
Player:  Name Changer, Skin Changer
Misc:    Player Alerts, Player List, Spotify HUD

## Name Changer / Skin Changer (local only)
Both only change what YOU see. Other players still see your real name and skin.
- Name Changer: replaces your username with the "Fake name" in your chat.
- Skin Changer: put a 64x64 skin PNG in <game folder>/config/pasta-client/ and type its file name in
  the "Skin file" box (default skin.png). "Slim arms" slider: 1 for slim (Alex) skins.

## Spotify HUD
Shows the current track, artist and progress at the top of the screen.
1. Get a temporary access token with the user-read-currently-playing scope (for example from the
   "Get Token" button in the Spotify Web API console at developer.spotify.com).
2. Paste it into the "Spotify token" box. Tokens last about an hour, then re-paste.
The token stays in memory and is only sent to api.spotify.com.

## Phone (Android) install
Java Edition only (PojavLauncher / Zalith). Not Bedrock, not stock iOS.
1. Upload this folder to a GitHub repo (keep .github), run Actions > "Build Pasta Client", download the artifact.
   Use pasta-client-0.3.0.jar, not the -sources jar.
2. Install Fabric Loader 1.21.4, put Fabric API + pasta-client.jar in mods.
3. Pause menu > "Pasta Client" button (top-center), or map a touch button to Right Shift.

If overlays don't show, try another renderer (ANGLE / Zink).
Untested: written without a compiler; Gradle/mapping versions may need updating from fabricmc.net/develop.
