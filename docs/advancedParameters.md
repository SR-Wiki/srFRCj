# Advanced parameters and result interpretation — Java/Fiji

Return to the [README](../README.md).

This guide covers the `sFRC`, `sFSC` and `srFRC` tools in the Fiji/ImageJ plugin. Parameter names match the Fiji dialogs. Enter pixel/voxel spacing in **nm**. Fiji may suggest values from **Image > Properties**, but the values confirmed in the dialog are used for analysis.

Go directly to the analysis you are using:

- [sFRC — global 2D resolution](#sfrc)
- [sFSC — lateral/axial 3D resolution and DRI](#sfsc)
- [srFRC — local resolution maps](#srfrc)

## How to interpret the results

| Output | Interpretation |
| --- | --- |
| Resolution (nm) | **Smaller** values indicate finer estimated resolvable detail; compare results using appropriate calibration and consistent settings. |
| `NaN` / unavailable | No usable estimate was obtained. Check warnings, sampling and correlation curves rather than treating it as zero. |
| srFRC map value `0` | Background or an invalid local estimate, **not** a measured resolution of 0 nm. Background zeros are excluded from foreground statistics. |
| `E_DRI` (sFSC) | Agreement between directional cutoffs and the DRI fit; **not** an error against ground truth or a confidence interval. |

Background **intensity thresholds** (0–255) are separate from FRC/FSC **correlation thresholds**. Use Float32 maps for quantitative analysis; RGB colour maps are for display.

| `Stack Batch Processing` | Computation | Display |
| --- | --- | --- |
| `true` (default) | All frames or adjacent pairs | sFRC: current-frame curve; srFRC: full numeric and colour stacks |
| `false` | Fiji's currently displayed frame or adjacent pair | Current-frame/pair result |

`Monitor Changes` (srFRC) overrides Batch and compares different background settings on the current frame/pair. sFSC analyzes one 3D volume or volume pair and does not use 2D-stack batching.

## sFRC

Global 2D resolution estimation for an image or image stack.

### sFRC input and settings

| Dialog label | Default | Meaning |
| --- | --- | --- |
| `Pixel Size` | Image Properties or previous input | Confirm actual XY spacing in nm; your input takes priority |
| `If SingleFrame` | `true` | Generate a diagonal pair from each frame; `false` uses paired frames |
| `Threshold` | `1/7 hard threshold` | Alternative: `3-sigma` |
| `Stack Batch Processing` | `true` | Process all frames/pairs or only the active frame/pair |
| `Apply Correction` | `true` | Apply the single-frame correlation correction |
| `Show Resampled images` | `false` | Show the generated resampled pair for inspection |

### sFRC curve extrapolation

Resolution normally uses a measured threshold crossing. If none is available, the fixed 1/7 threshold permits automatic extrapolation; `3-sigma` does not. Extrapolation extends beyond the measured frequencies without adding measured information.

A failed attempt leaves the estimate unavailable (`NaN`) and generates a warning. The selected threshold is not changed.

### sFRC stack batching and plotting

With Batch ON, an N-frame stack produces N single-frame results or N−1 overlapping paired results. With Batch OFF, only the active frame/pair is analyzed.

The FRC curve is displayed only for the current frame/pair, regardless of Batch mode. If the active frame has no following frame in paired mode, no substitute pair is displayed. There is no separate Plot control.

### sFRC results and windows

## sFSC

Lateral and axial resolution estimation for 3D volumes using DRI.

### sFSC input and calibration

Use the current 3D volume with `If SingleFrame=true`. For paired mode, set it to `false` and choose matching volumes using `3D Stack 1 (paired mode)` and `3D Stack 2 (paired mode)`. Both volumes must be open in Fiji and have matching dimensions. Selecting the same multichannel stack in both selectors uses its first two channels.

Enter `Pixel Size (XY)` and `Pixel Size (Z)` in **nm**. Angles are measured from the XY plane in **degrees**.

### sFSC parameters

| Dialog label | Default | Meaning |
| --- | --- | --- |
| `If SingleFrame` | `true` | Use one volume to generate a pair; `false` uses two volumes |
| `Pixel Size (XY)` | Image Properties or previous input | Lateral spacing in nm; user input takes priority |
| `Pixel Size (Z)` | Image Properties or previous input | Axial spacing in nm; user input takes priority |
| `Threshold` | `1/7 hard threshold` | Alternative: `3-sigma` |
| `Z Sampling` | `false` | Optionally use complementary odd/even Z planes |
| `Low Angle` | `15` | Lower DRI angle in degrees; recommended >10 |
| `High Angle` | `60` | Upper DRI angle in degrees; recommended <80 |
| `Number of Cones` | `5` | Positive integer; at least 5 is recommended |
| `Plot Fitting Curve` | `false` | Display the additional DRI-fit figure |
| `xyMode` | `slice` | Optionally choose `3D` for low-SNR data |
| `Motion Correction` | `false` | Align paired volumes |

`Advanced Settings` opens the optional controls. The available resolution profile is displayed independently of `Plot Fitting Curve`.

### sFSC curve extrapolation

Measured threshold crossings are preferred. Automatic extrapolation is supported only for the fixed 1/7 threshold. DRI attempts directional extrapolation **only when no direction has a usable measured crossing**; otherwise, it fits the available measured directions.

Failed attempts leave unavailable estimates as `NaN` and issue warnings. Extrapolation status is reported alongside the results.

### E_DRI: what the value means

E_DRI measures consistency between directional cutoffs and the fitted DRI model. It is the cosine-weighted relative RMS fitting error:

$$
E_{\mathrm{DRI}}=
\sqrt{\frac{\sum_i\cos\theta_i
[(k_i-k_{\mathrm{fit},i})/k_{\mathrm{fit},i}]^2}
{\sum_i\cos\theta_i}}.
$$

Here, `θ` is measured from the XY plane, `k_i` is a directional cutoff, and `k_fit,i` is the fitted cutoff. E_DRI measures fit consistency, not ground-truth accuracy.

| E_DRI (%) | Interpretation |
| --- | --- |
| `<10%` | Reliable under the DRI consistency criterion |
| `10–20%` | Use with caution |
| `>20%` | Unacceptable; do not accept the axial estimate |
| `NaN` / unavailable | No fit-error assessment |

For `E_DRI ≥10%`, inspect the directional curves and consider adjusting the fitting angles. Recommended limits are **Low Angle >10°** and **High Angle <80°**. A low E_DRI alone does not guarantee accuracy.

### Z sampling: manual control and axial interpretation

`Z Sampling` is **OFF by default** and is never enabled automatically. It uses complementary odd/even Z planes, which may reduce shared inter-plane correlations but also reduces axial sampling density.

If the axial estimate appears implausibly small, enable Z Sampling manually and compare the axial resolution, directional crossings and E_DRI.

### Warnings and prompts

| Situation | Fiji behavior |
| --- | --- |
| Missing crossing or failed extrapolation | Stage-specific warning; estimate may be `NaN` |
| Unusable DRI fit | DRI warning |
| `E_DRI ≥10%` | Interpretation and fitting-angle advice |
| Angles outside recommended limits | Advice; entered values are not automatically changed |
| Axial estimate with Z Sampling OFF | Conditional interpretation note |

The ImageJ log includes the E_DRI grading guide. The optional fitting figure is controlled by `Plot Fitting Curve`; the resolution profile is otherwise displayed when available.


## srFRC

Local resolution maps for 2D images and stacks.

### srFRC input and calibration

Use Fiji's current image or stack. Confirm `Pixel Size` in **nm**. In paired mode, adjacent frames are compared.

### srFRC parameters

| Dialog label | Default | Meaning |
| --- | --- | --- |
| `Pixel Size` | Image Properties or previous input | XY spacing in nm; user input takes priority |
| `If SingleFrame` | `true` | Generate a diagonal pair per frame; `false` uses paired frames |
| `Threshold` | `3-sigma` | Alternative: `1/7 hard threshold` |
| `Stack Batch Processing` | `true` | Process all frames/pairs |
| `Block Size` | `64` | Local analysis window in pixels |
| `Skip` | `2` | Step between analysis windows in pixels |
| `Use Automatic Background` | `true` | Automatic Otsu-based background detection |
| `Monitor Changes` | `false` | Compare different Otsu coefficients for the active frame/pair; disable Batch |
| `Background Intensity` | `30` | Manual background threshold (0–255) when automatic/monitor modes are off |
| `Patch Size` | `64` | Parallel patch width |
| `Calculation Backend` | `ND4J` | Alternative: `Legacy` |

The selected correlation threshold is the primary local criterion; internal fallback criteria are not additional user settings. The two backends retain their existing calculation behavior.

### srFRC background settings

`Monitor Changes` compares background thresholds using different Otsu coefficients on the active frame/pair and disables Batch. Otherwise, use automatic background detection or enter a manual background intensity.

### srFRC stack batching and plotting

With Batch ON, an N-frame stack produces N single-frame results or N−1 adjacent-pair results. With Batch OFF, only the currently displayed frame/pair is analyzed.

When Batch is ON and Monitor is OFF, two **complete stacks** are displayed: a **numerical stack** and an **RGB display stack with a separate colorbar for each frame**. Colours in different frames may represent different nm values. When Batch is OFF, only the active frame/pair is displayed. There is no separate Plot control.


