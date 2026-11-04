# Thai glyph rendering: investigation and implementation checklist

Status: the earlier `add8811a…` package is rejected for translation-only clipping at 150%. The replacement `6be6a26b…` package is locally verified and accepted by the user for active, blurred standby and opening/closing Thai translations at 150%. The original font/effects remain; Android and other physical scaling cases are unverified.

## Translation-path follow-up

The [26 October translation report](thai-translation-clipping-2026-10-26.md) reproduces the remaining defect in the actual subline's alpha and reveal buffers, corrects their measured paint bounds in both panels, and records the new failing/green regressions, 98/74 passing UI suites on ARM64 and emulated x64, 1,147 passing Android tests, compiled-panel captures and verified `6be6a26b…` Windows ZIP / `62d70d6a…` APK. The original font and intentional blur remain. These local results supersede the earlier missing translation coverage; physical Surface acceptance at 150% remains pending.

## Read-only findings

The native desktop registers `desktopThaiFontFamily()` from `wdb_bangna.ttf` and selects it for Thai app/translation language. `LocalThaiFontFamily` and `thaiTextStyle` forward that family to lyric text containing Thai. Android continues to use its own font registration; a desktop-only font observation is not evidence of an Android defect.

The existing font test establishes Thai coverage and family identity, but does not test ink extents, clipping or active animation. A fresh Java 21 ARM64 AWT outline probe confirms that U+0E43, U+0E44 and U+0E42 are present. At 100-pixel font size their outlines reach 123 pixels above the baseline while the reported ascent is 73.3 pixels. Their ink also extends left of the caret origin by 7.406, 21.406 and 26.594 pixels respectively. The outline therefore exists beyond both the nominal ascent and advance interval. This is direct font/metrics evidence, not yet proof of the exact Compose/Skia production clipping path.

The lyric renderer clips lifted words and growing characters using cursor positions and visual-line top/bottom bounds in `riseWith`, `growEach`, `glowGrown` and `sweepTo`. It draws active text through offscreen alpha masks. These are specific candidate clipping boundaries to test against the font's observed overhang. Plain/standby text, active sweep, growth and blur must be compared separately before selecting a correction. No font replacement, per-character offset or broad padding change is justified at this stage.

The eight relevant font/registration/renderer sources are preserved with SHA-256 hashes in the local `thai-glyph-20261025/before-rendering-source.zip` and manifest. The outline probe and raw output are retained alongside them. The two screenshots are described in the goal; their actual image files are not present in that goal attachment directory and have not been visually inspected in this investigation.

## Checklist before implementation

- [x] Inspect actual native/shared registration, Thai family selection and lyric animation/mask code.
- [x] Verify required Thai glyphs exist and inspect actual outline extents without altering the font.
- [x] Reproduce the lost strokes in the actual Compose/Skia lyric renderer and compare with unclipped reference text.
- [x] Separate font-metric, vertical-layer, horizontal-advance, shaping and per-character animation effects.
- [x] Add a failing rendering regression using the requested Thai strings, stacked marks and multilingual lines.
- [x] Implement the reproduced caret-, row- and effects-boundary corrections, retaining the font and existing effects/timing.
- [x] Verify focused static/animated, active/standby, density/font-scale, weight and wrapping cases; physical Windows scaling remains a separate check.
- [x] Run relevant ARM64/x64 desktop/shared/Android checks and record actual visual evidence plus physical-device limitations.
- [ ] Verify the corrected Thai glyphs on physical Surface and Android devices.
- [x] Reconcile the seven reviewed files and report into the separate full-history candidate, preserving both staging indexes; final publication review remains pending.

The broader upstream and AutoMix goals remain active. The earlier Surface acceptance of Audio Details, the specified automatic transition, touch scrolling, Home refresh and background FPS is preserved; it does not establish Thai glyph correctness.

## Confirmed causes and implemented behavior

1. The active rise/growth renderer sliced an entire shaped paragraph at caret-advance boundaries. Bangna's leading-vowel heads extend outside those boundaries. A real rendering regression loses 33/217, 36/203 and 67/233 bright reference pixels for U+0E43, U+0E44 and U+0E42. The new shared shaped-range painter isolates the actual glyph ink with transparent text spans, keeping graphemes/shaping clusters together. It applies the existing growth, lift, sweep/feather and glow data to those intact ranges. Non-Thai lines retain their existing path.
2. The row's rounded clip removes the enlarged leading overhang even after caret slicing is corrected. The 200% regression loses 105/740 reference pixels. Thai lead, translated or backing text can now paint outside the rounded background's rectangle; the background keeps its rounded shape.
3. The first updated full-panel capture still crops standby heads. The user correctly rejects that image. The production typography has a fixed strut: at font scale 2, the real text measures 82 pixels high with baseline 65.276. Measuring the same font/text without that strut gives 119 pixels and baseline 83.912. The low-alpha and blur layers rasterize into the smaller row bounds and cut ink outside them. Ordinary `Text` reproduces the crop, so it is not confined to the new painter. A production-typography regression captures only 7,643/23,464 units of upper-ink intensity before the correction.

The final correction expands only offscreen effect drawing bounds using the measured natural-versus-strut ascent/descent difference. It reuses the existing horizontal gutter for horizontal ink overflow. A compensating inner inset and adjusted transform origin preserve the item's reported size, text placement, scaling pivot and click bounds. No font-size, line-height, letter-spacing, opacity, blur-radius or playback-clock change is retained. A tested `Minimum` line-height attempt does not change these metrics and is reverted; both Thai style helpers are restored byte-for-byte to their captured originals.

The renderer is compiled from one shared source by both UI modules. Per-range paragraphs are cached during animation and retired afterward; settled wrapped-row paragraphs are retained for reuse. Brushes are created once per visual row/pass rather than for each animated range. No timer, coroutine, analysis job or playback scheduling change is introduced by this correction.

## Exact production/test files

- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/shaped/ShapedLyricLine.kt`
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt`
- `desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt`
- `sharedUi/src/jvmTest/kotlin/com/music/bitchord/ui/player/shaped/ThaiGlyphRenderingTest.kt`
- `desktopUi/build.gradle.kts`: shares the rendering/test sources and selects the existing JVM native runtime for the requested architecture.
- `sharedUi/build.gradle.kts`: supplies the existing platform runtime for the new rendering tests, selected by requested architecture. Dependency versions are unchanged.

The architecture selection also corrects a separate verified test-environment defect: an ARM64 Gradle driver with x64 test JVMs previously selected an ARM64 Skia runtime through `currentOs`. In that failed invocation, the engine's 610 x64 tests pass but 18 UI rendering cases fail with missing `skiko-windows-x64.dll.sha256`. Windows test-runtime classifiers now use the already selected target architecture. The controlled retry exits 0 with all three suites executed: 610 engine passes/six skips, 96 desktop-UI passes and 72 shared-UI passes. The Gradle daemon's actual ARM64 Java 25 executable and all three test executors' actual x64 Java 21 executables are verified in the log, alongside their PE architectures. This establishes the tested existing modern Gradle 9.5.1/toolchain combination; it is not a claim about the older wrapper, native x64 hardware, or improved build speed.

## Actual verification and remaining limitations

- Focused current suite: **12 tests passed**. It covers complete heads, 100/125/150/200% simulated density, 100/200% font scale, normal/extra-bold weight, held combining clusters, the required phrases, multilingual wrapping, glow/feather behavior, paragraph isolation, cache retirement/reuse, faint standby effects with production typography, and unchanged transformed row/click bounds.
- The first complete ARM64 run (before the final effects-boundary correction) passes 610 desktop tests/six skips, 90 desktop-UI tests, 66 shared-UI tests and 1,146 Android app tests/one skip. Raw XML and timestamps are archived. These earlier totals are not substituted for final-current-source verification.
- The real `LyricsPanel` and actual desktop font registration are rendered on the AWT owner thread at font scale 1 and 2. The initial fixture's asynchronous layout errors are retained and corrected in the fixture; they are not counted as a successful visual check. The current owner-thread capture completes without asynchronous failures.
- The corrected 200% capture has complete standby heads. An unchanged interior body region, `(80,575)..(900,605)`, is pixel-identical to the prior capture, supporting unchanged blur/opacity and placement there. This bounded pixel comparison does not prove every possible rendering state.
- User rejection image is preserved as `thai-glyph-20261025/user-standby-overhang.png`, SHA-256 `3fb96e1b29af7ce9f99576130a8397d2af242a6dda116a6e19d22bfa8653341f`. Current source-render evidence is `standby-overhang-corrected-crop.png` and `standby-layer-comparison.json` in that local audit directory.
- The first final-build attempt, `thai-layer-final-arm64`, fails before test execution because PowerShell splits a dotted Gradle property into two arguments. Its failure log is retained. The corrected argv-based run, `thai-layer-final-arm64-argv`, exits **0** after **478.17 seconds**: 96 desktop-UI passes, 72 shared-UI passes, 1,146 Android app passes/one skip, and 610 desktop passes/six skips on each of Java 21 and Java 25. All five suites execute in this invocation, and timestamp-checked raw XML is archived before changing test architectures. Windows portable, Android Dev APK and TV Dev APK assembly tasks succeed; Wear assembly is up-to-date, not a newly executed compilation. The separate `native-driver-x64-classifier-retry` exits **0** after **335.27 seconds**, with timestamp-checked XML archived separately in `final-x64-results`.
- A fresh `thai-final-binary-panel` probe compiles only the fixture, loading the real renderer and font registration from current built project classes/JARs. It passes without asynchronous errors and produces captures at font scale 1 and 2. The entire 200% image is pixel-identical to the previously reviewed corrected source capture. A native-pixel crop is preserved without resizing, contrast or brightness changes.
- New immutable candidates are under `rejected-surface-20261023/deliverables/2026-10-26-thai-overhang`. Windows ZIP: 408,630,107 bytes, SHA-256 `dae976493350f96360eb73368b29bf15e1c95d82a293079bd3c2b4e23cf87ce4`. Android ARM64 Dev debug APK: 99,297,669 bytes, SHA-256 `bf379fbabab58df7b844e58e825a9b0273a1aff68201c4951f944be9b07f3c2a`. Both CRC checks pass. The ZIP has 80 ARM64 PE files, 34 selected ARM64 native JAR resources, 1,353 matching current application classes, matching UI/shared JARs, the preserved Spotify helper and byte-identical Bangna font. The APK has only ARM64 native libraries (10 AArch64 ELF files), a valid signature, package `com.dev.bitchord`, version 1.8.1/code 29, and the same update certificate as the 25 October Dev APK. These are local test candidates, not release-signed or device-accepted deliverables.
- These are offscreen Compose/Skia captures and simulated density/font-scale checks. Physical Surface GPU rendering, OS display-scaling changes and actual Android device rendering still require device verification. No GPU improvement, long-session result or new package acceptance is claimed.
- The accepted 25 October ZIP `8156dc00…` predates these Thai corrections. Its Audio Details/automatic-transition acceptance remains valid for those features, but it does not verify this new source. The 26 October artifacts contain the correction; their physical-device acceptance is pending. No commit, push, upload or pull-request publication has occurred. The original authoritative staging-index hash remains `e06ebe4e519c5aea6049c651642786a80d8cad8815b8cf3b895aab346b079a7a`.

## Additional AutoMix model evidence

A read-only comparison uses the actual native mel frontend, pinned ARM64 ONNX runtime and a generated upstream 124-BPM loop. The existing 4,533,252-byte model returns 36 beats/four downbeats, BPM 124.6736, confidence 0.6336. The newer 23,138,816-byte upstream Web model returns 62 beats/15 downbeats, BPM 124.2920, confidence 0.95. Thus the upstream statement that the old model finds no beats is not reproduced literally. Neither model is replaced on this single-fixture evidence. Further grid/phase, CPU/memory and representative-audio validation is required before a native model change; elapsed values are observations, not benchmarks or perceptual-quality proof.


## Surface acceptance — verified package 6be6a26b

The user answers **“All intact”** for the Windows ARM64 ZIP with SHA-256 `6be6a26bbaf6545499e96850f2f03339fb39f7d9e118496855c7d2e99be2271d`: Thai translation heads/overhang at **150% Windows display scaling**, in **active, blurred standby, and opening/closing states**. This closes that reported Surface regression. The earlier `add8811a…` rejection remains valid historical evidence. This acceptance does not extend to Android, unasked lyric-size settings, other physical scaling values, Atmos, GPU usage or general AutoMix quality.
