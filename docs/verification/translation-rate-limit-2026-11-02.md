# Translation regression and shared HTTP 429 handling, 2 November 2026

Status: local implementation and behavioral verification completed. Live-provider acceptance remains unverified. Do not read this as a claim that Google's current restriction has cleared or that every real song now translates successfully online.

Source root: `E:\Development\BitChord-main\.work\upstream-v19-20261031\merge-checkout`. Use this nested checkout, not the outer directory or older clones.

## Established causes and evidence

The previous multilingual repair replaced marker batches with one request per distinct lyric segment. The same 40-line English fixture made 40 requests before this repair, versus 3 now. These are measured transport calls, not estimated performance results. An ordinary cold library could therefore create much more traffic. The previous translation cache identity also moved from version 3 to version 4 to reject potentially incorrect older translations, making previously visited tracks cold again.

The provider returned HTTP 429 for all six initial samples: short English, English sentence, English lyric-style phrase, the user's French example, Japanese, and Thai. The old client caught the IOException, returned null, then exposed ordinary Unavailable. It had no process-wide breaker or Retry-After handling. This establishes the observed rejection and the request-amplification defect. It does not establish Google's internal quota, exact enforcement threshold, other network users' contribution, or whether BitChord alone caused the restriction. Initial probe response headers were not preserved; no claim is made that a particular Retry-After value was received then. Translation-provider probes stopped after this finding.

Your extracted app at `C:/Users/Admin/Downloads/BitChord-1.8.1-dual-player-candidate3-windows-arm64-java25-portable/BitChord` contained the same pre-repair translation class as the audited source. Its settings selected `translation_language.0=th` and translated mode. Its 24 translation/romanization cache files included 23 version-3 entries and one version-4 Thai entry with 60 text slots, modified at 19:50:11 during the logged Sometimes session. That is consistent with a reusable new-cache result masking fresh-request failures. The old log did not record cache tier or translation requests, so the historical Sometimes display cannot honestly be classified as a memory hit, disk hit, or fresh provider success. A separately retrieved LRCLIB input variant did not match that existing cache key; it was correctly not substituted for the 60-slot entry. Existing cache bytes were preserved.

## Implemented behavior

- Shared coroutine-safe coordinator for translation and romanization, with at most two transport calls and coalescing of identical in-flight request bodies. Track-level coalescing remains.
- HTTP 429 opens a shared circuit. Queued work is bypassed, Retry-After seconds/HTTP-date are honored, and one recovery probe may run after the deadline. A stale in-flight success cannot close a newer circuit; obsolete work is cancelled.
- Local backoff uses bounded exponential growth and jitter. Recoverable network/408/5xx failures use 1.5–2 seconds initially, capped at 60 seconds. A 429 without a provider hint starts at 45–60 seconds, capped at 15 minutes. A longer provider Retry-After is never shortened. Eligibility controls subsequent user-triggered work; there is no automatic probing loop, proxy rotation, endpoint switching, or enforcement bypass.
- Valid complete version-4 results are checked before the circuit and remain usable offline. Version-4 identity/schema are retained, no valid translation caches were cleared, and legacy romanization version 3 remains intact. Twelve memory entries and the existing 2 MiB disk limit are preserved. Partial/failed/cancelled translations do not become complete cached successes.
- First/last anchors retain individual source evidence; remaining compatible script segments share bounded 3,500-character auto-detection batches. A batch's dominant source is not assigned to its members. Echoed/unusable members receive individual auto-detection, preserving mixed-language fallback without assuming Latin means English. Exact marker order is validated before mapping. Explicit pronunciation metadata stays associated with its own member; unmappable metadata causes individual detection.
- Valid source-erased batch results are retained instead of being overwritten by a neighbor-language retry. The previous short, individually uncertain French context retry remains tested.
- RateLimited is distinct from Unavailable and SameLanguage in shared results, both host adapters, and both UI implementations. The existing compact status says translation is rate limited and offers a later toggle retry. Recomposition does not retry. Supplied partial lines remain visible; a fresh 429 cancels the unfinished operation and retains original lyrics. Cached results remain preferred. No countdown or background polling timer was added.
- Structured diagnostics use cache/request fingerprints, sanitized target, memory/disk/miss, planned batch counts, resolved/failed/same counts, source language or und, outcome, HTTP/backoff reason, and final UI state. No full lyric text, credentials, or provider response bodies are logged. Existing debug sinks control delivery.
- No font, glyph padding, singer-lane renderer, playback engine, audio setting, dependency version, Gradle version, or JDK version was changed for this repair.

## Translation flow traced and tested

Saved toggle → requested target → per-track/lyrics/target Compose owner → Android/desktop host → target `th` (also normalizing `th-TH`) → versioned memory/disk lookup → track/request coalescing → script/clause segmentation and bounded batches → shared transport circuit → JSON/marker/echo/pronunciation validation → original-index rebuild with timingSource, alignment and backing vocals → cache complete answers only → immediate Ready or explicit RateLimited/Unavailable. Cancellation and ownership prevent older tracks or toggles publishing late answers.

## Tests and actual results

The final full ARM64 command ran shared, sharedUi and desktopUi JVM tests; desktop classes; Android dev-debug unit tests; TV/Wear Kotlin compilation; APK assembly; and portable ZIP generation. Exit 0, 391.04 seconds. Its reports contain 193 shared + 86 sharedUi + 126 desktopUi + 1,257 Android cases: 1,662 reported, zero failures/errors, one existing Android skip. Some independent tasks were up-to-date; compilation alone is not the behavioral evidence.

The final x64 command exited 0 in 148.41 seconds. The 86 sharedUi and 126 desktopUi cases actually executed under x64 JDK 21. Gradle reused the shared ARM result, so it was not counted as executed x64 coverage. After adding one extra mixed-batch fallback test, the entire shared suite was rerun on ARM64: 194 cases, zero failures/errors/skips. A targeted `:shared:jvmTest --rerun` then executed the 194 cases under `jdk-x64/jdk-21.0.12.1+1/bin/java.exe`, exit 0 in 29.98 seconds, zero failures/errors/skips. Combined verified x64 coverage is 194 shared + 86 sharedUi + 126 desktopUi = 406 distinct cases. ARM64's latest combined coverage is 1,663 distinct reported cases, with the same existing skip. The extra test changed test source only; packaged production code is unchanged.

Observed red tests precede repairs: 40 provider calls for the 40-line fixture and missing diagnostics; three coordinator failures for cooldown/coalescing/backoff; UI rejection mapped to Unavailable; eight-line pronunciation batch accepted phonetic text; valid English between French anchors overwritten by a forced-French retry; expired 429 followed by 503 misclassified as a new rate limit. The first four-line pronunciation reproduction already recovered through existing context and was not claimed as a failing reproduction. It was extended to eight lines and failed for the actual missed path. All corrected paths passed subsequent runs. A read-only reviewer rechecked the pronunciation, context and rejection-classification repairs with no remaining concrete P1/P2 findings.

The final diff check found nine newly inserted CRLF lines inside existing LF blocks in the mixed-ending sharedUi PlayerLyrics file. Only those nine endings were changed. The affected 86-case UI suite and APK assembly then passed, exit 0 in 56.36 seconds. The produced APK was SHA-256 byte-identical to the verified candidate. No Git newline configuration or unrelated lines were changed. The global diff check passed afterward.

Additional coverage: Retry-After seconds/date; no probes before expiry; one half-open probe; stale success generation; cancelled probe replacement; eight concurrent keys bounded to two transports; shared translation/romanization circuit; jitter and cap; non-retryable authorization response; mock HTTP 429 Response objects through the production response reader; preserved disk bytes; memory eviction followed by offline disk hit; fresh success after simulated recovery; repeated segments; partial/malformed/echo/oversize responses; marker corruption; usable English batch members retained while only echoed French members are retried; headers; target selection; rapid toggles; track switching; previous multilingual/cache/timing tests.

Seven actual LyricsPanel rendering checks in each UI module cover Japanese/Thai/English, both singer lanes, backing vocals, WDB Bangna and the previous font fallback, 150% density, large wrapping, opening/closing/reopening, highlight progression and real list scrolling. A current Japanese/Thai capture was visually inspected. These are actual renderer tests, not physical Surface or Android playback verification.

### Named-track integration measurements

Read-only LRCLIB inputs matched Sometimes/Mattyeux (210 seconds) and back to friends/sombr (199 seconds). Private fixtures were kept outside the repository. The same Java probe ran the installed pre-repair shared JAR and the repaired compiled classes, using actual LRC/background parsers, the public translation API and synthetic Thai provider replies. It checked target `th`, operation `t`, line count, alignment, timestamps and warm/offline cache use. No live translation requests were made. Synthetic replies do not establish semantic quality of full-song translations.

| Input | Parsed lyric rows | Before cold requests | After cold requests | Reduction | Warm/offline requests |
|---|---:|---:|---:|---:|---:|
| Sometimes | 49 | 38 | 3 | 92.1% | 0 |
| back to friends | 31 | 20 | 3 | 85.0% | 0 |
| Generated distinct English fixture | 40 | 40 | 3 | 92.5% | 0 |

French-only, Japanese-only and mixed English/French 40-line fixtures each use 3 calls with usable mock replies. If a batch echoes foreign members, only those members are retried; request counts may then be higher. These measurements are not a guarantee that every live song will require exactly three requests.

## A–M verification checklist

| Requirement | Evidence and scope |
|---|---|
| A Sometimes existing cache | Controlled named-song warm/offline hit verified. Existing user cache inspected/preserved; historical tier and exact 60-slot track association remain unverified. |
| B Sometimes cold | Full matched LRC input through both implementations with mock replies; 38→3. Live semantic acceptance remains unverified. |
| C Previously failing English | User named back to friends; full matched 199-second input tested, 20→3, warm zero. Live semantic acceptance remains unverified. |
| D English cold | 40-line cold fixture and semantic segment tests pass. |
| E French cold | 40-line cold fixture and user-provided French meaning fixture pass. |
| F Mixed cold | Full Sometimes input mock integration; long semantic matrix; echoed-French and valid-English retention tests pass. |
| G Japanese to Thai | Semantic fixtures plus Japanese/Thai production renderer pass. |
| H Same language | Only every eligible matching segment can produce SameLanguage; foreign echoes never become a Thai same-language success. |
| I Track switch while loading | Service cancellation and Compose late-result ownership tests pass. |
| J Rapid toggle | Eight on/off cycles plus track switch; only current answer reaches Ready. |
| K Offline cache | Memory and real disk round-trip after LRU eviction; zero provider calls and unchanged persisted bytes during 429. |
| L Connectivity restored | Simulated recovery after Retry-After returns a fresh result; expired-429/503 classification also checked. |
| M Multi-singer/new font | Actual renderer lane/font/density/animation tests pass; physical Surface confirmation on this candidate remains user testing. |

## Additional HTTP 429 requirements

Complete below means implemented and behaviorally verified locally; live provider acceptance remains a separate unverified gate.

| Item | Status | Evidence |
|---|---|---|
| 1 Respect Retry-After | Complete | Seconds/date parsing, early rejection, one expiry probe; a provider delay is not shortened. This follows [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html#field.retry-after). |
| 2 Bounded backoff/jitter | Complete | Injected-clock/random tests verify increasing delays, variation and caps; no polling loop. |
| 3 Shared cooldown/circuit | Complete | Concurrent keys, stale success and single/cancelled recovery probe tests. |
| 4 Coalesce and batch | Complete | Shared in-flight bodies, repeated segments, measured named-track counts, per-member fallback and language guards. |
| 5 Cache during limiting/offline | Complete | Cache-before-gate, memory eviction/disk round-trip, preserved bytes, zero warm calls. |
| 6 Distinct UI state | Complete | Both host/UI variants; actual player state/status/toggle test. |
| 7 Do not hide rejection | Complete | Explicit RateLimited through results and UI, HTTP/retry diagnostics, no conversion to ordinary Unavailable. |
| 8 Cancellation/memory/correctness | Complete | Ownership/cancellation/rapid-switch tests, bounded caches, multilingual/timing/rendering regression suites. |
| 9 Mock HTTP recovery | Complete | Mock 429 Responses pass through the production reader; time advances without contacting Google. |
| 10 No storms/cache clearing | Complete | Two transports, shared rejection suppression, single eligible probe, no new timers, valid cache bytes unchanged. |

## Changed files

Paths below are in the authoritative source root. All other source preimages were preserved; the complete upload manifest is a separate proposal.

- [app/src/main/java/com/music/bitchord/ui/player/AndroidPlayerHost.kt](E:/Development/BitChord-main/.work/upstream-v19-20261031/merge-checkout/app/src/main/java/com/music/bitchord/ui/player/AndroidPlayerHost.kt)
- [desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPlayerHost.kt](E:/Development/BitChord-main/.work/upstream-v19-20261031/merge-checkout/desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPlayerHost.kt)
- [desktopUi/src/commonMain/composeResources/values-th/strings.xml](E:/Development/BitChord-main/.work/upstream-v19-20261031/merge-checkout/desktopUi/src/commonMain/composeResources/values-th/strings.xml)
- [desktopUi/src/commonMain/composeResources/values/strings.xml](E:/Development/BitChord-main/.work/upstream-v19-20261031/merge-checkout/desktopUi/src/commonMain/composeResources/values/strings.xml)
- [desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricsTranslationState.kt](E:/Development/BitChord-main/.work/upstream-v19-20261031/merge-checkout/desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricsTranslationState.kt)
- [desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerHost.kt](E:/Development/BitChord-main/.work/upstream-v19-20261031/merge-checkout/desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerHost.kt)
- [desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt](E:/Development/BitChord-main/.work/upstream-v19-20261031/merge-checkout/desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt)
- [desktopUi/src/jvmTest/kotlin/com/music/bitchord/LyricsTranslationStateTest.kt](E:/Development/BitChord-main/.work/upstream-v19-20261031/merge-checkout/desktopUi/src/jvmTest/kotlin/com/music/bitchord/LyricsTranslationStateTest.kt)
- [shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsTranslation.kt](E:/Development/BitChord-main/.work/upstream-v19-20261031/merge-checkout/shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsTranslation.kt)
- [shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsTranslationRateLimit.kt](E:/Development/BitChord-main/.work/upstream-v19-20261031/merge-checkout/shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsTranslationRateLimit.kt)
- [shared/src/jvmTest/kotlin/com/music/bitchord/data/lyrics/LyricsTranslationRateLimitTest.kt](E:/Development/BitChord-main/.work/upstream-v19-20261031/merge-checkout/shared/src/jvmTest/kotlin/com/music/bitchord/data/lyrics/LyricsTranslationRateLimitTest.kt)
- [shared/src/jvmTest/kotlin/com/music/bitchord/data/lyrics/LyricsTranslationRecoveryTest.kt](E:/Development/BitChord-main/.work/upstream-v19-20261031/merge-checkout/shared/src/jvmTest/kotlin/com/music/bitchord/data/lyrics/LyricsTranslationRecoveryTest.kt)
- [shared/src/jvmTest/kotlin/com/music/bitchord/data/lyrics/LyricsTranslationTrafficTest.kt](E:/Development/BitChord-main/.work/upstream-v19-20261031/merge-checkout/shared/src/jvmTest/kotlin/com/music/bitchord/data/lyrics/LyricsTranslationTrafficTest.kt)
- [sharedUi/src/commonMain/composeResources/values-th/strings.xml](E:/Development/BitChord-main/.work/upstream-v19-20261031/merge-checkout/sharedUi/src/commonMain/composeResources/values-th/strings.xml)
- [sharedUi/src/commonMain/composeResources/values/strings.xml](E:/Development/BitChord-main/.work/upstream-v19-20261031/merge-checkout/sharedUi/src/commonMain/composeResources/values/strings.xml)
- [sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricsTranslationState.kt](E:/Development/BitChord-main/.work/upstream-v19-20261031/merge-checkout/sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricsTranslationState.kt)
- [sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerHost.kt](E:/Development/BitChord-main/.work/upstream-v19-20261031/merge-checkout/sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerHost.kt)
- [sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt](E:/Development/BitChord-main/.work/upstream-v19-20261031/merge-checkout/sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt)
- [sharedUi/src/jvmTest/kotlin/com/music/bitchord/ui/player/shaped/LyricsRateLimitUiTest.kt](E:/Development/BitChord-main/.work/upstream-v19-20261031/merge-checkout/sharedUi/src/jvmTest/kotlin/com/music/bitchord/ui/player/shaped/LyricsRateLimitUiTest.kt)
- [This verification document](E:/Development/BitChord-main/.work/upstream-v19-20261031/merge-checkout/docs/verification/translation-rate-limit-2026-11-02.md)

## Verified local artifacts

- Windows ARM64 portable ZIP: 428,874,718 bytes. SHA-256 `efddbb0fca59611687b6f2c906065aa4a4c0b510b0b3470a6e86f2e34037e15f`. Launcher/runtime PE AA64, Java 25 runtime, application bytecode Java 21; 1,434 application classes matched. All 913 shared and 469 desktopUi compiled classes matched packaged JAR entries, and the produced JAR bytes matched.
- Android ARM64 dev-debug APK: 122,017,307 bytes. SHA-256 `310ddc3a506ffeebff7147826e279e562100df58b3e0f9fa01e2f48398fcf172`. Thirteen ARM64 native libraries with exact notices; model/Phosphor/embedded-Wear checks passed. DEX contains the new coordinator and explicit RateLimited results/UI state, plus the retained translation-v4 and alignment markers. This is a debug candidate, not a release-signed publication.
- Independent package checks: native/Android verifier reports and `translation-code-binding.json` in the local package checkpoint. C: copies are SHA-256 verified.

## Preservation and publication

HEAD `da0d4d093fb388503d1165cd34312e319c1922dd`; pending merge parent `b690dc146009d4815825fa4a20815ef2e4ee8898`; existing local branch `integration/v19-comprehensive-20261031`. No new branch or commit was created for this repair. Index SHA-256 remains `61d923082a64342ead6d3994d37e111c351783bbffb0635da5ebdfa921d23dea`. Initial index, staged and unstaged binary diffs remain separate. Recovery covers 2,221 prior source files through the initial source ZIP plus four unchanged Java build-package preimages verified against the pre-implementation manifest; the supplemental archive preserves those legitimate source paths rather than treating their package name as build output.

GitHub destination is intended to be SiriusNovyx/BitChord-ARM64 after separate approval. No upload, push, PR creation, PR update, release, merge or publication occurred. All repair changes remain unstaged/untracked and unpublished, alongside the preserved historical work. The pending full upstream merge is not committed.

The new proposed manifest uses this nested checkout as repository-relative root. It includes legitimate project code, tests, resources and source/build inputs, and excludes internal .work/.git metadata, caches, credentials, local configuration, runtime profiles, private lyric fixtures/logs/backups, generated classes/APKs/ZIPs, build output and unrelated artifacts. Four tracked Python bytecode files remain locally preserved and are only proposed for exclusion from a future commit tree. Nothing has been staged or removed locally to enact that proposal.

## Remaining limitations and acceptance

The last live observation was HTTP 429; current provider availability was not re-probed. This existing endpoint is not given a new availability or official-service guarantee. The client cannot clear a provider restriction, and it will not bypass one. Fresh full-song online translation quality for the two named tracks, other supported languages and actual Surface/Android use still needs acceptance testing. Arbitrary changed Thai phonetics without trustworthy provider metadata cannot be semantically proven by local script checks. Automatic detection is not infallible, and unknown source evidence remains und. No sound, GPU, battery or timing-performance improvement is claimed.

The previous singer alignment and Thai-font code remain intact and behaviorally covered. Original lyrics remain available during rejection. Physical touch, audio output and GPU claims from the larger project are outside this repair's verification. The broader upstream integration and GitHub-publication goal is not declared complete here.

Copy/state review after implementation: the existing toggle and status area are reused; an actual player-state test invokes both toggle directions, checks the distinct visible status string and retained lines, and confirms no recomposition retry. No new overlay or unrelated UI flow was introduced. Full physical-app click-through was not performed.

## Final checklist

- [x] Read-only source/Git audit and separate recoverable staged/unstaged baseline.
- [x] Confirmed request amplification and observed HTTP 429; distinguish those facts from unknown provider quota history.
- [x] Shared coordinator, explicit rate-limit UI, bounded batching and safe multilingual fallback.
- [x] Cache, cancellation, timing, font and duet-alignment regression verification.
- [x] Actual ARM64 and x64 test execution verified; reused results identified rather than counted as new execution.
- [x] ARM64 ZIP/APK architecture, compiled-code binding and SHA-256 verified.
- [x] Exact changed files and a separate source-only upload proposal prepared; index preserved.
- [ ] Live full-song online acceptance for Sometimes and back to friends after provider permits requests.
- [ ] Historical Sometimes memory/disk/fresh tier cannot be reconstructed from logs that never recorded it.
- [ ] Separate GitHub publication approval, commits, push and PR work; none performed.
