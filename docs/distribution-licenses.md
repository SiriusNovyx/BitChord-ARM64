# Distribution license checkpoints

This document separates resolved asset provenance from release obligations that
still need evidence. It is not a complete legal-compliance certification.

## Android and TV icons

The former Jetpack_SF_Symbols 1.1.1 dependency and Apple-derived vector
implementations have been replaced. Android and TV now use a reviewed subset of
official Phosphor Regular/Fill assets. The exact revision, Git blobs, hashes,
semantic mapping and MIT text live in
`sharedUi/src/commonMain/phosphor/provenance.json` and `LICENSE`.
See [the icon maintenance guide](phosphor-icons.md) for reproducibility and actual
APK inspection commands. The Phosphor MIT notice is a shared Compose resource.
This replacement does not grant permission for any unrelated Apple font assets.

## Native audio components

Android's `app/src/main/assets/licenses/native-audio/` is also shared by TV. It
contains full license texts, original notices, source attribution and a
`provenance.json` inventory for the following compiled components:

| Component | Retained terms / provenance |
|---|---|
| Native LoopMix renderer / JNI | GPL-3.0-or-later |
| Shared LoopMix / Orchard-derived policy | AGPL-3.0-or-later |
| Signalsmith Stretch and Linear | Original MIT texts; vendored source revisions |
| Oboe | Apache-2.0; pinned 1.11.0 archive |
| libusb | LGPL-2.1-or-later; pinned 1.0.27 archive |
| Mornye-derived output code | Retained LGPL-3.0-only headers and original license text |
| DST decoder | LGPL-2.1-or-later, original notice and modification provenance |

The Mornye license retrieval revision records the exact copied license, not the
unestablished historical revision of the adapted code. The provenance file makes
that distinction explicit. Do not infer a code revision from the license fetch.

Native Desktop packages separately retain native LoopMix provenance, both
Signalsmith MIT texts, AutoMix AGPL attribution and the other notices described
in [the Windows third-party review](windows-arm64-third-party.md). FFmpeg terms
are specific to the actual bundled build; the inspected Windows ARM64 binary
reports LGPL-3.0-or-later. Other architecture artifacts need their own evidence.

## Open release gates

- Package notices must be inspected in each final artifact, including production
  signing/shrinking and architecture variants. A source notice alone does not
  prove that a shipped package contains it.
- Supply complete corresponding source for the exact combined build, including
  local modifications. Public upstream source does not cover unpublished fork
  changes. Preserve required source/build/installation and relinking materials
  for LGPL components, including statically linked libraries.
- Establish the exact Mornye adaptation revision and ONNX checkpoint/export
  provenance and notices. A current upstream repository is not proof of an
  unrecorded historical origin.
- Existing SF Pro OTF files remain separately restricted: their embedded license
  metadata limits Apple UI mockups and excludes software embedding. They remain
  unchanged in this icon migration. Permission or a separately reviewed font
  replacement is required before claiming distribution readiness.
- Confirm the exact native dependency variants, their included notices and their
  source/build provenance. A shared-library filename does not by itself prove
  that statically linked LGPL code is replaceable.

Local builds and behavioral tests can proceed independently. Do not describe
successful compilation or icon replacement as closing these remaining gates.
