# Model notices and reproducibility

`app/src/main/assets/licenses/models/` contains the exact original Beat This!
and Open-Unmix MIT texts, a contextual notice and `provenance.json`. Android
packages the default asset directory, and TV already shares it. Native Desktop
copies the same directory into its application JAR and portable license folder.
Web retains byte-identical copies under `web/app/public/models/licenses/`. Its
Vite configuration does not override the standard public/output directories or
public-directory copying. The inspected Vite 8.3.4 implementation copies `public`
into `dist` during a build, so these files accompany the existing model assets.
Actual rebuilt Web output inspection remains a separate gate.

## What is established

- Beat This!'s [pinned publisher README](https://github.com/CPJKU/beat_this/blob/b95c8ab0c58c2d9fcfd40508ae8dffbc05ac4f5c/README.md#license)
  identifies its code and published weights as MIT. Its exact license blob is
  retained, with no reconstructed copyright text.
- The [UMX-HQ deposit](https://zenodo.org/records/3370489), version 1.0.1, records
  MIT and the published vocals checkpoint checksum. The separate
  [Open-Unmix repository license](https://github.com/sigsep/open-unmix-pytorch/blob/fb672c9584997c2b05e148eeaa65b4c23ed4693b/LICENSE)
  is also retained unchanged. The UMXL non-commercial weight restriction is not
  a basis for claiming UMX-HQ's terms, or vice versa.
- Five local model paths are fingerprinted. The current Android/Web beat model,
  retained Native Desktop beat model and vocal model remain distinct artifacts.
  The source-selection evidence and intended package layout are recorded.

## What remains unresolved

The local ONNX filenames and source comments do not prove the exact original
checkpoint or conversion chain. For each model, retain:

1. The original checkpoint download URL, immutable version and strong fingerprint.
2. The conversion/export source revision and reproducible environment/arguments.
3. The ONNX opset, quantization algorithm and any calibration data/configuration.
4. A reproduced artifact fingerprint, or a documented numerical-equivalence test
   explaining unavoidable non-identical serialization.
5. The exact notices and provenance supplied with every final distribution.

The inventory intentionally does not claim that the listed UMX-HQ checkpoint
checksum proves the local ONNX was exported from it. This work does not change
models or claim model quality improvements. Other release gates are tracked in
[the distribution checkpoint](distribution-licenses.md).

## Offline checks

The verifier uses only Python's standard library. It checks the pinned original
license blobs and SHA-256 values, all inventoried local model bytes, and optional
actual package entries. It does not certify legal compliance or export lineage.

```text
rtk proxy python -X utf8 scripts/verify_model_notices.py
rtk proxy python -X utf8 scripts/verify_model_notices.py --apk path/to/phone.apk --apk path/to/tv.apk
rtk proxy python -X utf8 scripts/verify_model_notices.py --desktop-jar path/to/bitchord-app.jar
rtk proxy python -X utf8 scripts/verify_model_notices.py --portable path/to/BitChord-windows-arm64-portable.zip
rtk proxy python -X utf8 scripts/verify_model_notices.py --web-dist web/app/dist
```

For a portable ZIP, the check requires both the accessible
`BitChord/licenses/models/` originals and the same notices/models inside its
actual application JAR. Successful source checks must not be reported as
successful rebuilt-package checks.

Default source verification also requires the Web public notices to match the
canonical Android/Desktop source notices exactly. `--web-dist` requires an
output `index.html`, all four notice files and both Web model fingerprints. This
checks the supplied output directory; evidence that the current source was
actually built must still come from the executed Web build and its result.
