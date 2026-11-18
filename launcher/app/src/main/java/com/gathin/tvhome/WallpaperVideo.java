package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Plays a theme's wallpaper video behind Home while Home is visible (config wallpaper_video, off unless a
 * theme turns it on). The player is muted; the picture {@link WallpaperPlayer} shows stays on screen as
 * Home's first frame and the fallback: the video takes the picture's place only once its own first frame is
 * on screen, and any failure — no decoder for the file, a codec error — hides the video and leaves the
 * picture exactly as it was. Nothing here needs a permission or the network.
 *
 * <p>Rotation: when the theme carries a {@code videos/} folder, every {@code .mp4} in it plays once and
 * hands over to the next, in a random order drawn at each Home start, wrapping at the end. A clip the
 * decoder refuses is dropped and the rotation moves on, so one bad file costs one clip rather than the
 * whole wallpaper. The single {@code wallpaper.mp4} is still read as Home's first frame and the fallback,
 * and is the whole of the video when the theme has no {@code videos/} folder — there it loops as before.
 */
final class WallpaperVideo {
    /** Folder a theme puts its rotating clips in, beside wallpaper.mp4. */
    private static final String CLIPS_FOLDER = "videos";

    private final FrameLayout root;
    private final WallpaperPlayer picture;
    private final TextureView video;
    /** The clips to rotate through; a theme without a videos/ folder holds just the one file. */
    private final List<File> files;
    /** wallpaper_dim over the moving picture too: one black view, the same strength as the picture's. */
    private final View dim;
    private MediaPlayer player;
    private Surface surface;
    private int index;
    private boolean active;
    private boolean closed;
    private boolean failed;

    WallpaperVideo(Context context, FrameLayout root, Config config, WallpaperPlayer picture) {
        this.root = root;
        this.picture = picture;
        files = clipsFor(config.wallpaperVideo);
        video = new TextureView(context);
        video.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Index 0 puts the video under everything, the picture included: the video only shows once the
        // picture is hidden, so the swap from first frame to video is never a black frame.
        root.addView(video, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (config.wallpaperDim > 0) {
            dim = new View(context);
            dim.setBackgroundColor(Color.BLACK);
            dim.setAlpha(config.wallpaperDim / 100f);
            root.addView(dim, 1, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            dim = null;
        }
        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                surface = new Surface(texture);
                if (player != null) {
                    // The surface comes and goes with the window; a prepared player just moves to the new one.
                    player.setSurface(surface);
                    if (active) player.start();
                } else {
                    prepare(surface);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                // The player outlives the surface while Home's window is only hidden: let go of it here
                // instead of rendering into a surface the view has taken back.
                if (player != null) player.setSurface(null);
                surface = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture texture) {
                // A frame is on screen, so the picture can go; called for every frame, and setting the
                // same visibility again is free.
                setHidden(true);
            }
        });
    }

    /**
     * The clips to play. A theme's {@code videos/} folder wins, in a random order drawn once per Home
     * start so each start opens on a different clip; the single {@code wallpaper.mp4} is the fallback, so a
     * theme that only ever shipped the one file behaves as before.
     */
    private static List<File> clipsFor(File single) {
        List<File> out = new ArrayList<>();
        File dir = single == null ? null : single.getParentFile();
        File folder = dir == null ? null : new File(dir, CLIPS_FOLDER);
        File[] found = folder == null ? null : folder.listFiles(
                (d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".mp4"));
        if (found != null) {
            Arrays.sort(found);
            for (File f : found) {
                if (f.isFile() && f.length() > 0) out.add(f);
            }
        }
        if (out.isEmpty() && single != null && single.isFile() && single.length() > 0) out.add(single);
        if (out.size() > 1) Collections.shuffle(out);
        return out;
    }

    /** True while the video is on screen behind Home, and the picture behind it can stay still. */
    boolean showing() {
        return player != null && !failed;
    }

    void start() {
        active = true;
        if (failed || closed) return;
        if (player != null) {
            player.start();
            return;
        }
        // The player was released (memory pressure): the surface comes back with the view, and the
        // availability callback prepares a fresh player on it.
        video.setVisibility(View.VISIBLE);
        if (dim != null) dim.setVisibility(View.VISIBLE);
    }

    void stop() {
        active = false;
        if (player != null) player.pause();
    }

    /** Drops the decoder when Android asks the process to free memory; the picture takes the screen back. */
    void releaseForMemoryPressure() {
        release();
    }

    void close() {
        closed = true;
        release();
    }

    private void release() {
        if (player != null) {
            player.release();
            player = null;
        }
        // Back to the picture: the video's surface shows nothing without a player, and the dim belongs
        // to the video alone (the picture carries its own, painted in at decode).
        setHidden(false);
        video.setVisibility(View.GONE);
        if (dim != null) dim.setVisibility(View.GONE);
    }

    private void prepare(Surface target) {
        if (closed || failed) return;
        // No surface: the window is going away, and onSurfaceTextureAvailable prepares the clip when it
        // comes back. Treated as a failure here it would end the video for the rest of the session.
        if (files.isEmpty() || target == null) return;
        if (index < 0 || index >= files.size()) index = 0;
        File clip = files.get(index);
        try {
            MediaPlayer created = new MediaPlayer();
            created.setDataSource(clip.getPath());
            created.setSurface(target);
            // One clip loops itself; several hand over on completion instead (see handOver).
            created.setLooping(files.size() <= 1);
            created.setVolume(0f, 0f);
            created.setOnErrorListener((mp, what, extra) -> {
                // One clip the TV will not decode must not take the whole rotation down.
                handOver(true);
                return true;
            });
            created.setOnCompletionListener(mp -> handOver(false));
            created.setOnPreparedListener(mp -> {
                player = mp;
                if (active) mp.start();
            });
            created.prepareAsync();
        } catch (IOException | RuntimeException failure) {
            // setDataSource failed on this file (gone, unreadable): drop it like a decoder refusal rather
            // than ending the video, so the next clip still gets its turn.
            handOver(true);
        }
    }

    /**
     * The current clip is done (or unplayable): show the still picture while the next one is decoded, then
     * hand the same surface to a fresh player. The cut is through the wallpaper, so a hand-over — and a
     * clip dropped as broken — never shows black.
     *
     * @param dropCurrent true when the decoder refused this clip, so it is removed instead of played again
     */
    private void handOver(boolean dropCurrent) {
        if (closed || failed) return;
        if (files.size() <= 1) {
            // Nothing to hand over to: a single clip loops, and a single broken clip ends the video.
            if (dropCurrent) giveUp();
            return;
        }
        if (dropCurrent) {
            files.remove(index);
            if (files.isEmpty()) {
                giveUp();
                return;
            }
            if (index >= files.size()) index = 0;
        } else {
            index = (index + 1) % files.size();
        }
        setHidden(false);
        release();
        // The window is gone mid hand-over: keep the picture, and onSurfaceTextureAvailable prepares the
        // clip that is due when the view comes back.
        if (surface == null) return;
        video.setVisibility(View.VISIBLE);
        if (dim != null) dim.setVisibility(View.VISIBLE);
        prepare(surface);
    }

    /** A file the TV will not decode, or a decoder error mid-play: Home keeps the picture it already had. */
    private void giveUp() {
        failed = true;
        release();
    }

    private void setHidden(boolean hidden) {
        picture.setHidden(hidden);
    }
}
