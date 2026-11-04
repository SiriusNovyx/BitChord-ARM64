# Thai duet layout and multilingual translation, 2 November 2026

The user reported separated Japanese originals and Thai translations for the second singer in Mesmerizer on Windows, in both player layouts. A second report concerned English/French code switching in Sometimes producing missing or phonetic Thai translations. These fixes share no new playback clock, font replacement, dependency or application setting.

## Confirmed layout cause

Parsed TTML/LyricsPlus singer metadata reaches `LyricLine.alignment`; translation rebuilding preserves it. The parent lyric row deliberately owns the alignment for its original, translation and backing text. Both Windows layouts use that panel.

The regular swept-text renderer positions its intrinsic-width text with an absolute left/right Box anchor. Thai uses the shaped-ink renderer to preserve combining marks and glyph overhang. That renderer used a default-left Box and did not receive the resolved singer anchor. Right-aligned paragraph style alone cannot move a short, intrinsic-width child across its full-width parent. Consequently a right-lane Japanese original and a short timed Thai subtitle appeared on opposite sides.

An actual `LyricsPanel` regression reproduced the defect at 150% density: the timed Thai text node ended at 343px, whereas its untimed reference ended at 855px. The regular English control passed. Before/after raster images were inspected. The shaped source was byte-identical to the protected pre-v1.9 local tree; the merge carried this local defect forward rather than introducing a new singer-metadata mapping.

`SweptLyricLine` now forwards its existing `alignsRight(text, alignEnd, laneLocked)` result to `ShapedLyricLine`, whose Box uses the same absolute anchor as the regular path. No font bytes, font metrics, lyric spacing, timing, masking, rise or highlighting calculations changed. WDB Bangna remains supported. Tests explicitly load the previous SF Pro family/fallback and the custom Thai family.

## Confirmed translation causes

The former implementation batched potentially multilingual slots with `sl=auto`, chose one dominant response language for the entire song and could return SameLanguage for the whole track. A Latin-script retry passed all batches through Input Tools under that one language. A reduced unchanged-character count was accepted without proving that the result was a semantic translation. This made wrong routing, whole-track suppression and pronunciation substitution possible; it does not prove which exact provider response occurred in the user's original session.

Translation now evaluates independent line/clause/script segments through the existing provider's `dt=t` operation. Latin script does not imply English. Compatible repeated text is deduplicated, two requests can remain active, and the next request can start when either slot becomes free. Original ranges and separators reconstruct the exact line order; source metadata, backgrounds and bracketed section headers are retained. Header text continues to translate but is not used as language context for short vocal lines.

Valid provider source/confidence evidence is used conservatively. A short uncertain segment may retry only with agreeing reliable neighboring vocal detections within the bounded context window. An unusable hinted retry cannot discard a usable auto answer. Uncertain aggregate source is `und`; `mul` requires known multiple languages. SameLanguage requires every eligible segment to be confirmed as the target language.

Translation never invokes the romanization operation or Input Tools. Echoed foreign text, implausible Thai output and output matching explicit pronunciation metadata, including joined response parts, are rejected or retried. Failed/oversized segments retain their original text without shifting another line's answer; partial failures are not frozen in cache. Genuine `dt=rm` romanization remains a distinct operation.

Translation uses a version 4 identity/schema containing the algorithm/target/header distinction. Incompatible old translations are ignored. Existing version 3 romanization identities/schema remain readable in the same bounded cache directory. No unrelated cache is cleared; the2MiB disk bound, 12-entry memory bound and shared request deduplication remain.

## Behavioral evidence

- The initial layout regression failed on Thai placement and passed after the anchor correction. Seven actual panel cases cover primary/secondary singers, original-only views, Thai/Japanese/English subtitles, fallback/custom/explicit previous fonts, wrapping, large text, backing vocals and real-time opening/closing/reopening with playback-driven list scrolling. Per-frame singer anchors are checked. The same shaped source/tests compile in DesktopUI and SharedUI.
- Eighteen deterministic translation cases cover English/French and mixed clauses/scripts, Thai mixed with foreign text, per-segment SameLanguage, ambiguous context, malformed/echo/pronunciation responses, partial retryability, request capacity/deduplication, cancellation and cache migration/bounds. The timing fixture verifies unequal syllable durations and gaps through the original timing source; source words and syllables remain unchanged.
- Expected failing evidence was preserved before the routing fix, the header/capacity corrections and the contextual-retry/unknown-source/joined-pronunciation corrections. No test was disabled to obtain a pass.
- The reviewed focused run passed 33 cases: 18 multilingual, 1 cached timing, 7 panel cases in each UI module. The x64 broad run passed 385 cases: 178 shared, 84 SharedUI and 123 DesktopUI, with zero failures/errors/skips; native Desktop classes also compiled.
- A separate bounded live observation using the existing provider endpoint returned semantic Thai `ฉันนอนลงบนพรม` for the supplied French sentence and an English control. Explicit French `Je` returned `ฉัน`. An additional isolated call to the actual public translation API, using its default HTTP transport, returned ฉันรักคุณ and ฉันนอนลงบนพรม for an English/French pair, source mul, with original timestamps and the secondary singer preserved. Its second call used cache. These are sample service observations, not full-song player verification. Automated regressions do not rely on live availability.

Final ARM64 tests/package invocation exited 0 in 213.92 seconds. Actual XML records 1,642 cases: 178 shared, 84 SharedUI, 123 DesktopUI and 1,257 Android, zero failures/errors and one existing Android skip. Desktop classes and Android/TV/Wear compilation passed. Some unchanged Gradle tasks reuse valid UP-TO-DATE results; the recorded totals do not imply every case reran. The new local ARM64 portable ZIP has SHA256 2c7818a361b1f104fb95d1ad2cf5bc3067e6f8178d29f04f044779b20d0760f4; the ARM64 debug APK has SHA256 3b0c696821b9444e6d421d1750c7414bb7a8999cc22265afd2db271ed973e154. Runtime/launcher/native architecture and notices were inspected. Both fixed JVM library JARs and 81 changed-family compiled classes match the ZIP; Android DEX contains the new route/schema/alignment code. These are local development candidates, not published releases.

## Limits

Same-script code switching inside a clause without a clear boundary remains a single provider-auto segment. An arbitrary changed Thai phonetic string without usable pronunciation metadata cannot be semantically classified by these local guards. No universal translation-quality claim is made.

Visual checks use the actual renderer with synthetic Japanese/Thai duet fixtures, not a live Mesmerizer or Sometimes playback session. No Android device was connected, so SharedUI JVM raster tests and Android compilation do not establish physical Android rendering. Surface touch/audio/GPU and overall distribution permissions are outside this fix. GitHub publication remains separately unapproved; earlier2218-file publication predictions are superseded by this changed source.
