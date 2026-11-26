package com.pilipala.marcomovie;

import java.util.Locale;

/**
 * One row of a netdisk directory, shared by every drive browser so the 网盘 grid renders the same
 * for all of them. {@link #fid} is whatever that drive needs to act on the row: a 夸克 file id, or
 * (百度) the file's {@code fs_id} for a playable file and its {@code /path} for a folder.
 *
 * <p>Only data lives here: no HTTP, no Android APIs. Each drive client fills these rows and
 * {@link NetdiskScreen} renders them, which is why adding a drive does not touch the grid code.
 */
final class NetdiskEntry {

    /** Extensions the browser treats as playable, matched case-insensitively against the tail. */
    private static final String[] VIDEO_SUFFIXES = {
        ".mp4", ".mkv", ".ts", ".mov", ".avi", ".flv", ".wmv", ".m4v", ".webm", ".rmvb"
    };

    final String fid;
    final String name;
    final boolean dir;
    final long size;
    /**
     * The service itself says this row is a video ({@code format_type} / {@code category}). UC files
     * may have no extension at all, so the name alone is not enough there; 夸克/百度 leave it false
     * and the suffix check decides.
     */
    final boolean videoHint;

    NetdiskEntry(String fid, String name, boolean dir, long size) {
        this(fid, name, dir, size, false);
    }

    NetdiskEntry(String fid, String name, boolean dir, long size, boolean videoHint) {
        this.fid = fid == null ? "" : fid;
        this.name = name == null ? "" : name;
        this.dir = dir;
        this.size = size;
        this.videoHint = videoHint && !dir;
    }

    /** True for the container formats the player can open; extension match, case-insensitive. */
    static boolean isVideoName(String name) {
        if (name == null || name.isEmpty()) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String suffix : VIDEO_SUFFIXES) {
            if (lower.endsWith(suffix)) return true;
        }
        return false;
    }
}
