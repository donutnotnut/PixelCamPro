# PixelCam Pro

**PixelCam Pro** is an on-device computational photography Android camera app inspired by Google Pixel Camera. Built with **Jetpack Compose**, **Material 3 Expressive**, and **CameraX / Camera2 Interop**, it pairs a 120Hz-ready live viewfinder and real-time GPU shader grading with a custom multi-core **HDR+ Enhanced** burst-merging engine, handheld **Night Sight**, **Backlight anti-glare**, **Face & Motion Deblur**, **10-bit Ultra HDR**, and full **Pro manual controls**.

> **100% On-Device** — All computational photography, live histogram analysis, face tracking, and shader grading run locally on the device with no cloud dependencies or API keys required.

---

## Features

### Computational Photography & HDR+ Enhanced Engine
- **Multi-Frame HDR+ Pipeline (`HdrPlusEngine`)**: Choose between **Off**, **HDR+**, and **HDR+ Enhanced** with configurable **2-, 3-, or 5-frame** bursts.
- **Memory-Bounded Full-Resolution Streaming**: Evaluates sharpness on compact thumbnails (prioritizing detected faces and the active focus point) to pick the sharpest reference frame, then decodes and merges secondary frames sequentially up to 4096 px (12.5 MP) without holding multiple full-size bitmaps in RAM.
- **3-Stage Handheld Alignment & Ghost Rejection**: Combines coarse shift estimation, 1-px global refinement, and **8 × 6 local tile motion alignment** with exposure-normalized matching and automatic rejection of severely motion-blurred frames.
- **Edge-Preserving Temporal Merge & Chroma Denoise**: Precomputed LUT-accelerated temporal stacking boosts signal-to-noise ratio in shadows while suppressing color noise.
- **Halo-Free Local Tone Mapping**: Bilinearly interpolated multi-pass Gaussian local tone mapping avoids tile seams and dark halos around bright specular highlights.
- **Sabre Deconvolution & Face / Motion Deblur**: Directional micro-blur estimation and deconvolution restore fine edge sharpness across both standard and backlit portraits while skipping already-sharp frames.
- **Backlight Picture Mode (`Auto` / `On` / `Off`)**: Detects high-contrast backlit scenes and underexposed faces against bright backgrounds, applying exposure bracket fusion, anti-flare dehazing, and shadow clarity.
- **10-bit Ultra HDR (Android 14+ / API 34+)**: Captures native hardware Ultra HDR when supported or synthesizes a 10-bit `Gainmap`, preserving intact MPF/GContainer metadata when saved to `DCIM/Camera`.
- **Cinematic Effect**: Optional out-of-the-box filmic tuning (subtle deeper exposure, richer contrast, and vibrant color) that stays synchronized between live preview and final saved output.
- **Advanced Pipeline Tuning**: Fine-tune highlight recovery, local tone mapping, temporal shadow denoise, Sabre deconvolution sharpening, backlight anti-flare dehaze, backlight shadow clarity, and motion/face deblur strength directly from Settings.

### Low Light & Night Sight
- **Adaptive Low-Light Detection (`LowLightDetector`)**: Modes for **Auto**, **On**, and **Off**. Fuses Camera2 hardware AE telemetry (ISO + shutter load, damped for Pixel Post-RAW & Low Light Boost) with live frame luminance and hysteresis to prevent flicker around light thresholds.
- **Exposure-Equivalence Long Exposure**: Automatically plans **3–5 handheld long-exposure frames** (100–280 ms per frame) that extend shutter speed while lowering ISO to cut noise and protect bright light sources from clipping.
- **Live Hold Feedback & Smart Handoff**: Displays a 360° circular shutter hold progress indicator during capture, automatically manages HDR/Ultra HDR state while in low light, and monitors the ambient light sensor (lux) to exit smoothly when returning to daylight.

### Pixel Camera Services (CameraX Extensions)
- **Native Vendor Extensions Toggle**: Optionally enable Google's native **Pixel Camera Services** (`Auto`, `HDR`, `Night` via `androidx.camera:camera-extensions`).
- **Hybrid Night-Only Routing**: On devices that expose only Pixel Night Sight via extensions, daylight captures stay on PixelCam Pro's custom HDR+ & Native Ultra HDR pipeline while low-light scenes automatically switch to native Pixel Night Sight.

### Live Viewfinder, GPU Grading & Framing HUD
- **Real-Time AGSL Shader Preview (Android 13+)**: Uses a branch-light FP16 AGSL `RuntimeShader` for non-linear Shadows and Highlights curves, and zero-FBO hardware layer `ColorMatrix` grading for Contrast, Vibrancy, EV, Cinematic look, and White Balance (with automatic Android 12 and below fallback).
- **High-Refresh-Rate Ready**: Requests the display's highest native refresh rate on resume so animations, reticle tracking, and live dials remain fluid up to 120 fps.
- **Multi-Lens & Continuous Zoom**: Optical lens pill switcher (e.g., ultra-wide, wide, telephoto based on device hardware), pinch-to-zoom, and expandable continuous zoom slider.
- **Tactile Focus & Exposure Reticle**: Single-tap to focus, long-press to lock **AE/AF**, and drag vertically on the reticle to adjust live exposure compensation.
- **Hardware Face Tracking**: Real-time Camera2 face detection mapped through `FaceCoordinateMapper` (handling digital zoom crop, sensor orientation, aspect-ratio crop, and front-camera mirroring) with smooth viewfinder reticles.
- **Pro Viewfinder Overlays**:
  - Live 32-bin **RGB + Luma Histogram** (`LiveFrameAnalyzer`)
  - Gravity/accelerometer **Horizon Spirit Level** (`HorizonSensorMonitor`)
  - Composition Grids: **Off**, **3 × 3 (Rule of Thirds)**, **Golden Ratio**, and **4 × 4**
  - **Highlight Clipping Indicator** (>97% luminance warning) & **Focus Edge Sharpness** Laplacian readout
  - Live **Optical Telemetry HUD** (ISO, shutter speed, Kelvin WB, focal length, aperture, and active computational badges)

### Pro Manual Controls, Tone Sliders & Custom Looks
- **9-Tab Pro Controls Panel**:
  - **Exposure (EV)**: `-3.0` to `+3.0 EV`
  - **Shadows**: `-100%` to `+100%`
  - **Highlights**: `-100%` to `+100%`
  - **Contrast**: `-100%` to `+100%`
  - **Vibrancy**: `-100%` to `+100%`
  - **White Balance**: `2500K–8500K` continuous slider + quick Kelvin presets (`Auto`, `3200K`, `4400K`, `5400K`, `6500K`, `7800K`)
  - **ISO**: `Auto`, `50`–`6400`
  - **Shutter Speed**: `Auto`, `1/4000s` to `4"`
  - **Manual Focus**: `AF`, `∞`, `1.0m`, `0.33m`, `Macro`, plus continuous diopter slider
- **Tone Adjustments & Saved Pixel Looks**: Adjust Shadows, Highlights, Contrast, and Vibrancy in a dedicated panel, save named custom **Looks** (`TonePreset`) to Jetpack DataStore, and switch or delete them anytime from the horizontal **Pixel Looks** bar.
- **Quick Settings Drawer**: Fast toggles for Flash (`Off` / `Auto` / `On`), Night Sight, Self-Timer (`Off` / `3s` / `10s`), Aspect Ratio (`4:3` / `16:9`), HDR+ mode, and front-camera selfie mirroring.

### Computational Photo Inspector & Media Storage
- **Built-In Photo Inspector (`PhotoInspectorScreen`)**: Filmstrip viewer with animated transitions, Share/Delete actions, and automatic `COLOR_MODE_HDR` window activation on Android 14+ when inspecting Ultra HDR images.
- **Per-Capture Telemetry**: Inspect frames merged, alignment confidence %, dynamic range gain (`+EV DR`), compute duration (ms), ISO, shutter speed, white balance, resolution, Look name, processing badges (`Night`, `Backlight`, `Face Deblur`, `Ultra HDR`), and post-process luma histogram.
- **Standard Gallery Storage**: Saves JPEGs directly to `DCIM/Camera` (`PXL_YYYYMMDD_HHMMSS_SSS_NN.jpg`) via `MediaStore` with EXIF metadata (and RAW DNG output support).

### Material 3 Expressive UI & Haptics
- **Dynamic Dark Theming**: Uses Material You dynamic dark colors on Android 12+ (with a deep obsidian camera fallback), edge-to-edge display, and spring-physics motion (`M3ExpressiveMotion`).
- **Morphing Polar-Harmonic Shapes**: Custom `ExpressivePolygonShape` buttons and badges (cookies, clovers, bursts, circle-to-square morphs) paired with permission-free system `CameraHaptics`.

---

## Architecture & Project Structure

PixelCam Pro follows an **MVVM / Unidirectional Data Flow** architecture built on Kotlin Coroutines, `StateFlow`, Jetpack Compose, and Preferences DataStore.

```text
app/src/main/java/com/example/
├── MainActivity.kt                        # Edge-to-edge host activity & max refresh rate setup
├── camera/
│   ├── hardware/
│   │   ├── CameraController.kt            # CameraX + Camera2 interop, Extensions, bursts, AF/AE, face detection
│   │   └── HorizonSensorMonitor.kt        # Gravity/accelerometer spirit level & ambient lux sensor flows
│   ├── model/
│   │   └── CameraModels.kt                # Domain models, AGSL shader, ColorMatrix/Kelvin math, LowLightDetector, FaceCoordinateMapper
│   └── processing/
│       ├── HdrPlusEngine.kt               # Multi-core HDR+ burst alignment, merge, tone mapping, deblur & Ultra HDR gainmap
│       ├── LiveFrameAnalyzer.kt           # Throttled YUV_420_888 32-bin RGB+Luma histogram, sharpness & face-ROI luma
│       └── MediaStoreRepository.kt        # DCIM/Camera JPEG/Ultra HDR/DNG storage, EXIF tagging & MediaStore queries
├── data/
│   └── SettingsDataStore.kt               # Jetpack DataStore persistence for camera settings & custom Tone Presets
└── ui/
    ├── screens/
    │   └── CameraScreen.kt                # Live viewfinder, gestures, AGSL/ColorMatrix grading, lens bar & shutter deck
    ├── viewmodel/
    │   └── CameraViewModel.kt             # UI state orchestration, capture pipelines, auto Night Sight & Backlight logic
    ├── components/
    │   ├── CameraHaptics.kt               # Tactile shutter, detent & lock feedback
    │   ├── ExpressiveM3Components.kt      # M3 Expressive controls, springs, sliders, chips & segmented cards
    │   ├── ExpressiveShapes.kt            # Morphable polar-harmonic polygon shapes
    │   ├── PhotoInspectorScreen.kt        # Computational photo inspector, filmstrip & HDR display mode
    │   ├── PixelLooksBar.kt               # Saved Tone Preset carousel
    │   ├── PixelSettingsSheet.kt          # Full camera & HDR+ tuning settings screen
    │   ├── ProControlsPanel.kt            # 9-dial manual exposure & focus panel
    │   ├── ToneAdjustmentsPanel.kt        # Live Shadows/Highlights/Contrast/Vibrancy & Presets drawer
    │   └── ViewfinderOverlays.kt          # Grids, horizon level, focus/EV reticle, face boxes, histogram & HUD
    └── theme/
        ├── Color.kt
        ├── Theme.kt                       # Material You dynamic dark theme & Expressive shapes
        └── Type.kt
```

---

## Tech Stack & Requirements

| Component | Details |
| :--- | :--- |
| **Language** | Kotlin `2.4.21` (Java 11 bytecode) |
| **Android Gradle Plugin** | `9.1.1` |
| **SDK Levels** | `minSdk = 24` (Android 7.0), `targetSdk = 36`, `compileSdk = 36` |
| **UI** | Jetpack Compose (BOM `2024.09.00`), Material 3, Material Icons Extended, Coil Compose `2.7.0` |
| **Camera** | AndroidX CameraX `1.7.0` (`core`, `camera2`, `lifecycle`, `view`, `extensions`) |
| **Persistence & Async** | Jetpack DataStore Preferences `1.1.7`, Kotlinx Coroutines `1.10.2` |
| **Testing** | JUnit 4, Robolectric `4.16.1`, Compose UI Test, Coroutines Test, AndroidX Espresso |

### API-Level Feature Matrix
- **Android 7.0+ (API 24+)**: Full HDR+ Enhanced multi-frame capture, Night Sight, Backlight mode, Pro controls, live histogram, and `ColorMatrix` preview grading.
- **Android 12+ (API 31+)**: Material You dynamic dark color scheme.
- **Android 13+ (API 33+)**: Real-time FP16 AGSL `RuntimeShader` non-linear preview tone curves.
- **Android 14+ (API 34+)**: Native & synthesized 10-bit **Ultra HDR** Gainmap capture and `COLOR_MODE_HDR` playback in the Photo Inspector.

---

## Getting Started

### Prerequisites
- **Android Studio** (latest stable supporting AGP 9.1+ and API 36)
- **JDK 11+**
- A physical Android device is strongly recommended to test Camera2 manual sensor controls, multi-lens switching, hardware face detection, Pixel Camera Services extensions, and 10-bit Ultra HDR.

### Build & Run Locally
1. Clone or download this repository and open the `pixelcam-pro` folder in **Android Studio**.
2. Wait for Gradle sync to complete.
3. Select a connected Android device (or emulator with virtual camera enabled) and click **Run**, or build via Gradle:

```bash
# Build debug APK
./gradlew assembleDebug

# Install directly onto a connected device
./gradlew installDebug
```

> **Note on Signing & Secrets**: No `.env` file or `GEMINI_API_KEY` is needed to run PixelCam Pro. Release builds automatically fall back to the default debug keystore when no upload keystore is present locally.

---

## Testing

The project includes comprehensive unit and Robolectric test suites covering computational imaging math, multi-frame burst processing, low-light planning, face coordinate transforms, and `CameraViewModel` state logic:

- [`ExampleRobolectricTest.kt`](app/src/test/java/com/example/ExampleRobolectricTest.kt) — HDR+ burst merging, tile alignment, halo-free tone curves, motion-blur rejection, Sabre sharpening, Ultra HDR gainmaps, Cinematic grading, Night Sight exposure equivalence, Backlight fusion, Pixel Camera Services routing, Tone Presets, and motion deblurring.
- [`FaceAndNightSightTest.kt`](app/src/test/java/com/example/FaceAndNightSightTest.kt) — Face coordinate zoom/rotation mapping, handheld Night Sight planner bounds, Pixel Post-RAW ISO damping, highlight protection, and selective face deblurring.

Run all local unit and Robolectric tests with:

```bash
./gradlew testDebugUnitTest
```

---

## Permissions

- `android.permission.CAMERA` — Required for the live viewfinder, focus/metering, and photo capture.
- `android.permission.WRITE_EXTERNAL_STORAGE` (`maxSdkVersion="28"`) — Used only on Android 9 and below for saving captured photos to `DCIM/Camera` (Android 10+ uses scoped `MediaStore` with no storage permission prompt).
