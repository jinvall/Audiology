# Audiology2 - Icon Specification for Toolbar/Menus

## Overview
All icons should follow **Material Design 3** guidelines with **SRP theme colors**. Provide both **filled** and **outlined** variants for each icon state.

## Color Palette (from SRP theme)
| Role | Hex | Usage |
|------|-----|-------|
| Primary | `#00D4AA` | Active states, accent |
| Primary Container | `#004D3A` | Hover backgrounds |
| On Primary | `#FFFFFF` | Icons on primary |
| Secondary | `#65637E` | Inactive states |
| On Surface | `#E6E0E9` | Default icons |
| On Surface Variant | `#CAC4D0` | Disabled icons |
| Error | `#FF4D6D` | Record, delete |
| Error Container | `#93000A` | Error backgrounds |
| Surface | `#1A161E` | Backgrounds |
| Surface Variant | `#2E2833` | Button backgrounds |

---

## Icon Sizes & Formats

### Base Sizes (dp)
| Context | Size | Touch Target |
|---------|------|--------------|
| Toolbar/Action Bar | 24dp | 48dp |
| Small Toolbar | 20dp | 40dp |
| Tab Bar | 24dp | 48dp |
| Floating Action Button | 24dp | 56dp |
| Dialog/Menu | 24dp | 48dp |
| List Item Leading | 24dp | 48dp |
| Track Header | 20dp | 40dp |
| Clip Handle | 16dp | 32dp |

### Density Variants (px)
| Density | 16dp | 20dp | 24dp | 48dp | 56dp |
|---------|------|------|------|------|------|
| mdpi (1x) | 16 | 20 | 24 | 48 | 56 |
| hdpi (1.5x) | 24 | 30 | 36 | 72 | 84 |
| xhdpi (2x) | 32 | 40 | 48 | 96 | 112 |
| xxhdpi (3x) | 48 | 60 | 72 | 144 | 168 |
| xxxhdpi (4x) | 64 | 80 | 96 | 192 | 224 |

### File Format
- **Primary**: **SVG** (vector, scalable) - master source
- **Runtime**: **WebP** (lossless) for each density bucket
- **Naming**: `ic_<name>_<variant>.webp` (e.g., `ic_play_filled.webp`)

---

## Icon List by Category

### 1. Transport Controls (Toolbar - 24dp filled)
| Name | Description | States | Notes |
|------|-------------|--------|-------|
| `ic_play` | Play triangle | filled, outlined | Primary action - use primary color |
| `ic_pause` | Two vertical bars | filled, outlined | Shown during playback |
| `ic_stop` | Square | filled, outlined | Error color when recording |
| `ic_rewind` | Double triangle left | filled, outlined | |
| `ic_fast_forward` | Double triangle right | filled, outlined | |
| `ic_record` | Circle | filled (red), outlined | Filled = recording, outlined = ready |
| `ic_record_disabled` | Circle with slash | filled | When no track armed |
| `ic_loop` | Circular arrows | filled, outlined | Loop playback |
| `ic_shuffle` | Crossed arrows | filled, outlined | Shuffle tracks |

### 2. View Toggles (Toolbar - 24dp outlined default, filled when active)
| Name | Description | States |
|------|-------------|--------|
| `ic_waveform` | Sine wave | outlined, filled |
| `ic_spectrogram` | Vertical bars (FFT) | outlined, filled |
| `ic_split_view` | Two panes split | outlined, filled |
| `ic_fullscreen` | Expand arrows | outlined, filled |

### 3. Zoom & Navigation (Toolbar - 24dp outlined)
| Name | Description | States |
|------|-------------|--------|
| `ic_zoom_fit` | Rectangle with corners | outlined |
| `ic_zoom_selection` | Rectangle with selection | outlined |
| `ic_zoom_in` | Plus in magnifier | outlined |
| `ic_zoom_out` | Minus in magnifier | outlined |
| `ic_pan` | Hand / grab | outlined, filled (active) |

### 4. Snap Grid (Toolbar - 24dp outlined, filled when active)
| Name | Description | States |
|------|-------------|--------|
| `ic_snap_off` | Magnet with slash | outlined, filled |
| `ic_snap_seconds` | "1s" label | outlined, filled |
| `ic_snap_frames` | "F" frame icon | outlined, filled |
| `ic_snap_beats` | Quarter note | outlined, filled |
| `ic_snap_measures` | Measure lines | outlined, filled |
| `ic_snap_8th` | Eighth note | outlined, filled |
| `ic_snap_16th` | Sixteenth note | outlined, filled |
| `ic_snap_triplet` | Triplet bracket | outlined, filled |

### 5. Track Controls (Toolbar - 24dp outlined)
| Name | Description | States |
|------|-------------|--------|
| `ic_add_track` | Plus in circle | outlined, filled |
| `ic_delete_track` | Trash can | outlined, filled (error) |
| `ic_track_up` | Up arrow | outlined |
| `ic_track_down` | Down arrow | outlined |
| `ic_track_color` | Palette | outlined, filled |

### 6. Clip Editing (Toolbar - 24dp outlined)
| Name | Description | States |
|------|-------------|--------|
| `ic_split` | Scissors / cut line | outlined, filled |
| `ic_duplicate` | Two overlapping pages | outlined, filled |
| `ic_delete_clip` | Trash can | outlined, filled (error) |
| `ic_trim` | Bracket handles | outlined |
| `ic_fade_in` | Fade curve left | outlined, filled |
| `ic_fade_out` | Fade curve right | outlined, filled |
| `ic_gain_envelope` | Envelope curve | outlined, filled |

### 7. Undo/Redo (Toolbar - 24dp outlined)
| Name | Description | States |
|------|-------------|--------|
| `ic_undo` | Curved arrow left | outlined, filled (when available) |
| `ic_redo` | Curved arrow right | outlined, filled (when available) |

### 8. Track Header Buttons (20dp outlined, filled when active)
| Name | Description | Active Color | States |
|------|-------------|--------------|--------|
| `ic_mute` | Speaker with slash | Secondary | outlined, filled |
| `ic_solo` | "S" in circle | Gold `#FFD700` | outlined, filled |
| `ic_arm` | Record circle | Error `#FF4D6D` | outlined, filled |
| `ic_monitor` | Headphones | Primary | outlined, filled |
| `ic_lock` | Padlock | On Surface Variant | outlined, filled |
| `ic_track_menu` | Three dots vertical | On Surface | outlined |

### 9. Clip Handles (16dp filled)
| Name | Description | Color |
|------|-------------|-------|
| `ic_drag_handle` | Six dots (2x3) | On Surface Variant |
| `ic_resize_left` | Left bracket | Primary |
| `ic_resize_right` | Right bracket | Primary |
| `ic_fade_handle` | Diamond | Primary |
| `ic_gain_point` | Circle with dot | Primary |

### 10. Menu Items (24dp outlined)
| Name | Description |
|------|-------------|
| `ic_new_project` | Document plus |
| `ic_open_project` | Folder open |
| `ic_save_project` | Save / floppy |
| `ic_save_as` | Save with pencil |
| `ic_import_audio` | Upload + music note |
| `ic_export_audio` | Download + music note |
| `ic_export_mixdown` | Download + layers |
| `ic_project_settings` | Gear |
| `ic_preferences` | Sliders |
| `ic_help` | Question circle |
| `ic_about` | Info circle |

### 11. Dialog/Confirmation (24dp filled)
| Name | Description | Color |
|------|-------------|-------|
| `ic_check` | Checkmark | Primary |
| `ic_close` | X | On Surface |
| `ic_warning` | Triangle exclamation | Warning `#FFAB00` |
| `ic_error` | Circle exclamation | Error |
| `ic_info` | Circle info | Primary |

### 12. State Indicators (16-20dp)
| Name | Description | Colors |
|------|-------------|--------|
| `ic_playing` | Animated play | Primary |
| `ic_recording` | Pulsing circle | Error |
| `ic_paused` | Two bars | Secondary |
| `ic_clipping` | Exclamation in triangle | Error |
| `ic_muted` | Speaker slash | On Surface Variant |
| `ic_solo_active` | "S" circle | Gold |

---

## SVG Guidelines

### ViewBox
```xml
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="24" height="24">
```

### Stroke & Fill
```xml
<!-- Outlined variant -->
<path stroke="#E6E0E9" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" fill="none" d="..."/>

<!-- Filled variant -->
<path fill="#00D4AA" d="..."/>
```

### Naming Convention
```
ic_<category>_<name>_<variant>.svg
Examples:
ic_transport_play_filled.svg
ic_transport_play_outlined.svg
ic_track_mute_filled.svg
ic_track_mute_outlined.svg
ic_clip_split_filled.svg
```

---

## Directory Structure
```
theme-pack/
├── icons/
│   ├── svg/                    # Master SVGs
│   │   ├── transport/
│   │   ├── view/
│   │   ├── zoom/
│   │   ├── snap/
│   │   ├── track/
│   │   ├── clip/
│   │   ├── edit/
│   │   ├── track_header/
│   │   ├── clip_handles/
│   │   ├── menu/
│   │   ├── dialog/
│   │   └── state/
│   └── webp/                   # Runtime assets
│       ├── mdpi/
│       ├── hdpi/
│       ├── xhdpi/
│       ├── xxhdpi/
│       └── xxxhdpi/
```

---

## Android Integration

### drawable XML (for vector drawables)
```xml
<!-- res/drawable/ic_transport_play.xml -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="?attr/colorPrimary"
        android:pathData="M8,5v14l11,-7z"/>
</vector>
```

### Theme Attributes (attrs.xml)
```xml
<attr name="iconColorPrimary" format="reference|color"/>
<attr name="iconColorSecondary" format="reference|color"/>
<attr name="iconColorError" format="reference|color"/>
<attr name="iconColorOnSurface" format="reference|color"/>
<attr name="iconColorOnSurfaceVariant" format="reference|color"/>
```

### Usage in Layout
```xml
<ImageButton
    android:id="@+id/btnPlay"
    android:layout_width="48dp"
    android:layout_height="48dp"
    android:src="@drawable/ic_transport_play"
    android:background="?attr/selectableItemBackgroundBorderless"
    android:contentDescription="@string/desc_play" />
```

---

## Priority Order for Design

### Phase 1 (Core Transport) - Start Here
1. `ic_play` (filled/outlined)
2. `ic_pause` (filled/outlined)
3. `ic_stop` (filled/outlined)
4. `ic_record` (filled red/outlined)
5. `ic_rewind` / `ic_fast_forward`

### Phase 2 (Editor Toolbar)
6. `ic_waveform` / `ic_spectrogram`
7. `ic_zoom_fit` / `ic_zoom_selection`
8. `ic_snap_off` + snap modes
8. `ic_undo` / `ic_redo`

### Phase 3 (Track/Clip)
9. `ic_add_track` / `ic_delete_track`
10. `ic_split` / `ic_duplicate` / `ic_delete_clip`
11. `ic_mute` / `ic_solo` / `ic_arm`

### Phase 4 (Advanced)
12. Track header icons
13. Clip handles
13. Menu items
14. State indicators

---

## Delivery Checklist
- [ ] All SVGs in `theme-pack/icons/svg/<category>/`
- [ ] WebP exports for all 5 densities in `theme-pack/icons/webp/<density>/`
- [ ] Naming convention verified
- [ ] Stroke width consistent (2dp for 24dp icons)
- [ ] Colors match SRP palette exactly
- [ ] Touch targets meet 48dp minimum
- [ ] RTL/mirror support tested