package com.pilipala.marcomovie;

/**
 * One title as a site carries it: identity plus the metadata the UI and the ledger need. Carries no
 * playable lines, no raw payload and no vendor structure type — lines stay with the player path.
 */
final class MediaRef {
    final String siteKey;
    final String id;
    final String title;
    final String poster;
    final String year;
    final String area;
    final String genre;
    final String remarks;
    final String doubanScore;
    /** Folder/action cards keep their route: without them a hit became a plain detail card. */
    final String action;
    final String tag;
    /** Detail-page metadata: synopsis, cast and the Douban subject id for the rating badge. */
    final String description;
    final String actor;
    final String director;
    final String doubanId;

    MediaRef(
            String siteKey,
            String id,
            String title,
            String poster,
            String year,
            String area,
            String genre,
            String remarks,
            String doubanScore,
            String action,
            String tag,
            String description,
            String actor,
            String director,
            String doubanId) {
        this.siteKey = siteKey == null ? "" : siteKey;
        this.id = id == null ? "" : id;
        this.title = title == null ? "" : title;
        this.poster = poster == null ? "" : poster;
        this.year = year == null ? "" : year;
        this.area = area == null ? "" : area;
        this.genre = genre == null ? "" : genre;
        this.remarks = remarks == null ? "" : remarks;
        this.doubanScore = doubanScore == null ? "" : doubanScore;
        this.action = action == null ? "" : action;
        this.tag = tag == null ? "" : tag;
        this.description = description == null ? "" : description;
        this.actor = actor == null ? "" : actor;
        this.director = director == null ? "" : director;
        this.doubanId = doubanId == null ? "" : doubanId;
    }
}
