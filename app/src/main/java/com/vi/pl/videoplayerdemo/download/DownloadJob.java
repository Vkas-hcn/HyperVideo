package com.vi.pl.videoplayerdemo.download;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "download_jobs")
public class DownloadJob {
    @PrimaryKey @NonNull public String id;
    @NonNull public String pageUrl;
    @NonNull public String platform;
    @NonNull public String title;
    @NonNull public DownloadState state;
    public int progress;
    public String failureReason;
    public String tempPath;
    public String mediaUri;
    public long createdAt;
    public long completedAt;

    public DownloadJob(@NonNull String id, @NonNull String pageUrl, @NonNull String platform,
                       @NonNull String title, @NonNull DownloadState state, int progress,
                       String failureReason, String tempPath, String mediaUri,
                       long createdAt, long completedAt) {
        this.id = id;
        this.pageUrl = pageUrl;
        this.platform = platform;
        this.title = title;
        this.state = state;
        this.progress = progress;
        this.failureReason = failureReason;
        this.tempPath = tempPath;
        this.mediaUri = mediaUri;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
    }
}
