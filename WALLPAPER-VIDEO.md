# Wallpaper video and drift — a fork branch

This branch carries the two wallpaper features the main launcher does not ship, both off by default and set by a theme's `config.json` ([issue #2734](https://github.com/Gathin23/cleanyourtv/issues/2734); the maintainer keeps video out of the main launcher, whose target is 1 GB sets, so this lives here as a fork):

- **`wallpaper_motion`** — the still wallpaper slowly zooms and drifts while Home is visible (up to 8 % over about seven minutes and back), a Ken Burns pan with no per-frame allocation. Off under `reduced_motion`. Upstream as [PR #2733](https://github.com/Gathin23/cleanyourtv/pull/2733); this branch carries it underneath the video layer.
- **`wallpaper_video`** — a theme's `wallpaper.mp4` plays behind Home, muted and looping, pushed like `wallpaper.jpg` (`tools/theme.sh`). The still picture stays as Home's first frame and the fallback: the video takes the screen only once its own first frame is up, and any failure — a file the TV will not decode, a decoder error — leaves the picture exactly as it was. The player pauses whenever Home is not visible and releases under memory pressure; no new permissions, no manifest change, no network.

`themes/demo-video/` is a demo theme whose picture and video are generated (ffmpeg's `gradients` source), so this branch carries no third-party art.

Measured on a Sony BRAVIA 4K VH22 (Google TV 12, MediaTek MT5835, 3 GB): about 30 MB PSS with a 1080p loop playing (about 22 MB with no wallpaper at all), scrolling 0.73 % janky / p90 8 ms, and Netflix → HOME round-trips and reboots all resume the loop.

Build and try it as usual:

    launcher/build-and-install.sh
    TV_HOST=<tv ip> tools/theme.sh themes/demo-video      # or your own theme with a wallpaper.mp4

License: this repo's.
