# Classic and Modern player layouts

The user requests both wide-player designs with current playback infrastructure. References are the two supplied screenshots, `Screenshot 2026-10-20 064003.png` (Modern) and `Screenshot 2026-10-20 064013.png` (Classic). The user explicitly chose to retain the existing responsive portrait player on narrow Android windows; the distinct arrangements apply to wide/landscape windows.

## Source and ownership

`desktopApp` hosts `desktopUi`; Android/TV host `sharedUi`. The UI modules deliberately have exclusive consumers because they share package/resource names. The modern native arrangement is in desktopUi's LandscapePlayer; sharedUi and frozen fork tree `3a741066a29be3d2c6e2a213f09fda266d354eac` preserve the original artwork-left, controls-right layout. Recovered source blobs and SHA256 records are under the original workspace's `.work/dual-player-20261020`.

## Design

- A common `PlayerLayout` preference has stable `modern`/`classic` storage values. Missing or invalid values select Modern. A new localized Appearance preference is named **Player Layout**, described **Choose your preferred Now Playing interface.** Both options are visible and keyboard/touch accessible using the existing choice controls.
- Follow-up user request: desktop Discord keeps the last track visible as paused while playback is paused or stopped, without a running timer. Resume recalculates timing from the current engine snapshot; explicit disable and actual application quit clear presence. This applies independently of layout and does not create a separate player session.
- Android persists through AppSettings/SharedPreferences. Native desktop persists through DesktopPersistence, including portable-data mode. Each host exposes its current layout through PlayerSettingsSource.
- NowPlayingScreen retains its existing song, scrub, lyrics translation, queue, artwork and output state above layout selection. Only the wide arrangement changes. No layout key may recreate the player host or engine, and selection may not issue playback commands.
- Desktop Modern remains the current artwork/transport-left, lyrics-right design. Classic reuses the recovered arrangement and current controls, restoring origin caption, volume, and bottom lyrics/output/queue actions. The latest quality/AutoMix indicators, anchored song menu and output adapters remain connected.
- SharedUi retains Classic's existing wide arrangement and adds the Modern arrangement using the same slots and modern player state. Narrow portrait remains unchanged. TV uses its separate `TvNowPlayingScreen`/`TvNowPlayingStage`, not either referenced layout: D-pad focus ownership, deferred scrub commits and a five-second transport timeout belong to that implementation. Replacing it with the phone/desktop slots would remove those remote-navigation contracts. Its dedicated UI remains unchanged; a TV-specific dual arrangement is not implemented in this change. Wear is a remote control without either full-player layout and keeps its current interface. Web has its own responsive player and is outside these native screenshot designs.
- Transitions are immediate, without crossfading two live player trees. Lyrics and queue remain explicit panel state, separate from layout preference. Default Modern may present lyrics in its right-hand column without changing the saved panel or translation preference.

## Verification

Test preference default/invalid values, stable round trips and desktop restart restoration before adding the UI wiring. Build shared/sharedUi/desktopUi and Android/desktop ARM64/x64; preserve the Java21 bytecode contract. Exercise both layouts on the exact rebuilt candidate with an equivalent song/pane/window size. Compare track, position, paused state, volume, device, queue and lyrics selection across repeated switches. Check menus, lyrics, queue, minimize/restore, normal shutdown and persisted selection after restart. Keep previous packages as a pre-feature baseline and rebuild deliverables after verification.

The detailed user specification authorizes this implementation; do not introduce a new approval gate for routine design choices. No commit, push or publication is authorized.
