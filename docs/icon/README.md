# App icon

`source_icon.png` is Shane's ProGallery icon design (1254×1254, with wordmark).

Android adaptive icons are masked to the launcher's shape (circle, squircle, …), so the
launcher icon is generated from the design rather than used as one flat image:

- **Foreground** — the cards-and-aperture artwork cut out from the design (flood-fill from
  the crop edge removes the navy background and frame glow) and scaled so it sits inside
  the 66 dp safe zone.
- **Background** — navy gradient matched to the design's interior.
- **Monochrome** — single-colour silhouette for Android 13+ themed icons.
- The **wordmark** is omitted from the launcher icon (illegible at launcher size; the
  launcher shows the app name). The full design, wordmark included, is shown on the
  About screen (`res/drawable-nodpi/about_logo.webp`).

Regenerate: `python3 make_layers.py` (in a folder containing `source.png`), then
`python3 preview.py` for `preview.png`; copy the outputs into `res/mipmap-*/` at
108/162/216/324/432 px (mdpi→xxxhdpi). `compare.png` shows original vs launcher icon.
