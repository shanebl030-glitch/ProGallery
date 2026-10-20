# App icon source

`layers.py` holds the icon geometry (108×108 adaptive canvas) as SVG; `render.py` renders
`preview.png` (circle, squircle and rounded masks at 36–192 px, themed mode, light wallpaper)
with headless Chromium. The Android resources mirror this geometry exactly:

- `app/src/main/res/drawable/ic_launcher_background.xml` — gradient + glow
- `app/src/main/res/drawable/ic_launcher_foreground.xml` — cards, sunset photo, play badge
  (group transform = `FG_TF`)
- `app/src/main/res/drawable/ic_launcher_monochrome.xml` — themed icon (`MONO_TF`)
- `app/src/main/res/mipmap-anydpi/ic_launcher*.xml` — adaptive-icon wiring

Keep all artwork inside the 66 dp safe circle (radius 33 around 54,54).
