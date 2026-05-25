# Device config backups

These files are backups of the Android app SharedPreferences from deployed phones.
They may include XML comments for settings that are kept under older preference
keys for compatibility. Those comments are documentation for these checked-in
backups only; Android may remove them if it rewrites the live preferences file.

Source path on each phone:

```text
/data/data/cam.fenetre.android/shared_prefs/fenetre_camera.xml
```

Pull a backup with:

```bash
adb -s <ip>:<port> shell run-as cam.fenetre.android cat shared_prefs/fenetre_camera.xml
```

Current backups:

- `p6p-fenetre-camera.xml`
- `s20-fenetre-camera.xml`

Compatibility notes:

- `ssim_area` is still the stored preference key, but the app exposes it as the
  sky area in the UI, status, and metrics.
