# Phosphor icons

Android, Android TV and their shared UI use `com.music.bitchord.ui.icons.AppIcons`.
Call sites name the action or state; the facade chooses a cached vector. Regular
is the default. Explicit selected heart, star, dislike, completed and appropriate
filled states use Fill. These are the only two weights in the vendored subset.
The independent native Desktop UI retains its existing icon implementation.

## Source and compatibility

The official [Phosphor core](https://github.com/phosphor-icons/core) assets are
pinned to commit `2b75f3ad12b420c9504ef05df8d2564a28f8500e`. The subset contains
103 SVGs, 47,378 source bytes, supplying 110 semantic properties. Original Git
blob IDs, SHA-256 values, selected weights and mappings are recorded in
`sharedUi/src/commonMain/phosphor/provenance.json`. The source SVGs are kept
outside Compose resources and are not intended to be copied into APK assets.

Phosphor documents community Compose ports, but this application needs only a
small subset. Its existing Compose `ImageVector` APIs support the official paths
directly. Generated common Kotlin avoids a wrapper dependency, dependency-version
changes and shipping a complete icon catalog. Lazy vector properties parse each
selected path once; screens use typed properties, without name lookup or reflection.

The generator preserves each official 256-unit viewport and the standard 24 dp
icon box. It adds no tracing, artificial stroke weight or Apple-derived geometry.
Directional semantic icons opt into Compose RTL mirroring. Existing buttons retain
their modifiers, content descriptions, input handlers and selected-state conditions.

## Updating the subset

Keep only reviewed Regular/Fill assets from an explicitly pinned official revision.
Update the corresponding manifest hashes and semantic mappings, then run:

```text
python scripts/generate_phosphor_icons.py
python scripts/verify_phosphor_icons.py
```

The generator runs offline and refuses unexpected SVG constructs or changed source
hashes. `--check` verifies reproducibility without rewriting files. Add new assets
only when a semantic icon needs them. Do not replace the manifest with an unpinned
network catalog or introduce runtime asset lookup.

The shared JVM `PhosphorIconRenderingTest` checks every semantic icon against an
independent Skia SVG decoder in light and dark colors, selected Regular/Fill pairs
and directional RTL sizes. Compose's cubic arc conversion and SVG native conics
can have different subpixel edges; the test checks both ink envelopes, weighted
area, centroid and tint rather than expecting identical arc rasterization.
Android/TV compilation, unit tests and actual APK checks remain separate gates.

## Notices and package verification

The official MIT text is retained in the vendor directory and generated unchanged
at `sharedUi/src/commonMain/composeResources/files/Phosphor-MIT.txt`, so both
Android and TV package the attribution with shared UI resources. Verify actual APKs:

```text
python scripts/verify_phosphor_icons.py --apk path/to/app.apk --apk path/to/tv.apk
```

The verifier checks notices, generated-source consistency, source-only SVG exclusion
and obsolete symbol class/resource names. It does not establish unrelated font,
native DSP, provider or distribution compliance. Those remain separate reviews.
