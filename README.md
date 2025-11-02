# Voxel RT (GPU fallback)
- OpenGL 4.3 compute ray marcher
- **GPU fallback world ON by default** (press `H` to toggle SSBO/CPU world)
- G = debug gradient, H = toggle GPU fallback, P = present-test
- WASD + mouse, Space/Ctrl = up/down, E cycles block to place, mouse L/R mine/place


## Sky, Soft Shadows & Torch
- **Sky model:** Preetham (1999) analytic daylight (`uSkyModel=1`) with `uTurbidity`, driven by `uSunDir`.
- **Soft sun shadows:** jittered-cone sampling around the physical sun (`uSunAngularRadius`), averaged visibility over `uSunSoftSamples` rays.
- **Player torch:** point/area light at the camera (`uTorchPos`), inverse-square falloff, optional softness via `uTorchRadius` & `uTorchSoftSamples`.

### Tuning (Engine.java)
- `uTurbidity` ~ 2–3 clear, 6–8 hazy.
- `uSunAngularRadius` ≈ 0.00465 rad (~0.266°).
- `uSunSoftSamples` & `uTorchSoftSamples`: quality/perf tradeoff (8–16 good).

### Build
```bash
mvn -q clean package
mvn -q exec:java -Dexec.mainClass="com.example.voxelrt.Main"
```
Tip: add `--enable-native-access=ALL-UNNAMED` to VM options for LWJGL on Java 21+/25.
