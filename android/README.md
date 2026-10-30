# Chamberla Collector (Android app)

Your collection checklist as a standalone app, with a **Scan barcode** button.

## Build the APK (Android Studio)
1. Install Android Studio and open this folder (`GameChecklistApp`).
2. Let it sync Gradle. Then: Build > Build Bundle(s) / APK(s) > Build APK(s).
3. The APK is at `app/build/outputs/apk/debug/app-debug.apk`. Copy it to your phone and open it
   (allow "install unknown apps" for your file manager/browser when asked).

## Scanning
- Tap **Scan barcode**, scan a game's UPC.
- First time: the app looks the barcode up online (UPCitemdb free tier, ~100 lookups/day) and suggests
  matching games from your lists. Tap the right one; it is checked off and the barcode is remembered.
- Next time that barcode is scanned it checks off instantly, with no lookup.
- If the lookup finds nothing, type the title in the box and pick it.

## Notes
- Your ticks, added titles and remembered barcodes are stored on the phone only.
- The game data is a snapshot of your Oct 29, 2026 export. To refresh it, replace `app/src/main/assets/index.html`.
- Scanning uses Google's code scanner (needs Google Play services, no camera permission).

## Box art (IGDB)
Open the app, tap **Box art setup**, and enter your Twitch/IGDB Client ID and Client Secret. They are stored only in the app's private storage on the phone (never in the page or this project). Covers load as you scroll an open platform and are cached; tap a cover for the larger image and description. Use a regenerated secret, not one pasted in chat.

## Views and tools
- **Show:** cycles All, Owned only, Missing, Wishlist. **View:** list or big icons (slider sets tile size).
- Star any game you don't own to add it to the wishlist. **Set goal** on a platform pins a progress card at the top.
- Pick a condition (Loose, CIB, New, Digital, Key card) on any owned game; values follow it.
- **Tools:** backup/restore, missing-games report, upgrade finder (Loose to CIB), duplicates.
