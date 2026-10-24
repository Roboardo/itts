# Trascrivi

Android app that transcribes Italian voice messages (e.g. WhatsApp voice notes)
to text, **entirely on the phone**. No audio is ever uploaded.

Share a voice note from WhatsApp → *Trascrivi*: a popup shows the text with
punctuation, ready to copy or share. You can also open the app and pick an
audio file.

## Install

Requirements: Android 10+, 64-bit ARM phone (practically every phone since ~2019).

**With automatic updates (recommended):**

1. Install [Obtainium](https://github.com/ImranR98/Obtainium/releases) (`app-arm64-v8a-release.apk`).
2. In Obtainium tap **Add App**, paste `https://github.com/Roboardo/itts` and confirm.
3. Tap **Install**. Obtainium will notify you of new versions.

**Manually:** download the latest `trascrivi-*.apk` from
[Releases](https://github.com/Roboardo/itts/releases) and open it
(allow "install unknown apps" when asked).

On first use the app downloads the speech model (~670 MB, Wi‑Fi recommended).
After that it works offline.

## Credits

- Speech model: [NVIDIA Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3),
  CC-BY-4.0, ONNX int8 export by the sherpa-onnx project.
- Inference: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0),
  [ONNX Runtime](https://github.com/microsoft/onnxruntime) (MIT).

## Building

```sh
./gradlew assembleDebug     # dev build, installs as "Trascrivi (dev)"
./release.sh 1.1            # signed release + GitHub release
```

Release signing reads `~/keystores/itts-release.properties`
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`), or the path given
with `-Pitts.signing=...`. The sherpa-onnx AAR is downloaded into `app/libs`
on the first build.
