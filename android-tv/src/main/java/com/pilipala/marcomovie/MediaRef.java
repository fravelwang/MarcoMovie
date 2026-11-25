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

    MediaRef(
            String siteKey,
            String id,
            String title,
            String poster,
            String year,
            String area,
            String genre,
            String remarks,
            String doubanScore) {
        this.siteKey = siteKey == null ? "" : siteKey;
        this.id = id == null ? "" : id;
        this.title = title == null ? "" : title;
        this.poster = poster == null ? "" : poster;
        this.year = year == null ? "" : year;
        this.area = area == null ? "" : area;
        this.genre = genre == null ? "" : genre;
        this.remarks = remarks == null ? "" : remarks;
        this.doubanScore = doubanScore == null ? "" : doubanScore;
    }
}
