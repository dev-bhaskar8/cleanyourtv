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

/**
 * Plays a theme's wallpaper.mp4 behind Home while Home is visible (config wallpaper_video, off unless a
 * theme turns it on). The player is muted and loops; the picture {@link WallpaperPlayer} shows stays on
 * screen as Home's first frame and the fallback: the video takes the picture's place only once its own
 * first frame is on screen, and any failure — no decoder for the file, a codec error — hides the video
 * and leaves the picture exactly as it was. The file is read from the theme folder; nothing here needs
 * a permission or the network.
 */
final class WallpaperVideo {
    private final FrameLayout root;
    private final WallpaperPlayer picture;
    private final File file;
    private final TextureView video;
    /** wallpaper_dim over the moving picture too: one black view, the same strength as the picture's. */
    private final View dim;
    private MediaPlayer player;
    private boolean active;
    private boolean closed;
    private boolean failed;

    WallpaperVideo(Context context, FrameLayout root, Config config, WallpaperPlayer picture) {
        this.root = root;
        this.picture = picture;
        file = config.wallpaperVideo;
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
                Surface surface = new Surface(texture);
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

    private void prepare(Surface surface) {
        if (closed || failed) return;
        try {
            MediaPlayer created = new MediaPlayer();
            created.setDataSource(file.getPath());
            created.setSurface(surface);
            created.setLooping(true);
            created.setVolume(0f, 0f);
            created.setOnErrorListener((mp, what, extra) -> {
                giveUp();
                return true;
            });
            created.setOnPreparedListener(mp -> {
                player = mp;
                if (active) mp.start();
            });
            created.prepareAsync();
        } catch (IOException | RuntimeException failure) {
            giveUp();
        }
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
