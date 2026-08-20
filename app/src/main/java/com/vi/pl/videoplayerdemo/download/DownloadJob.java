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
    @NonNull public String sourceType;
    public int selectedHeight;
    @NonNull public String selectedFormatId;
    @NonNull public String resolvedMediaUrl;
    @NonNull public String manifestUrl;
    @NonNull public String resumeFilePath;
    public long totalBytes;
    public long downloadedBytes;
    @NonNull public String etag;
    @NonNull public String lastModified;
    public long contentLength;
    public boolean acceptRanges;
    @NonNull public String resourceIdentity;
    @NonNull public String parseStage;
    public int parseVersion;
    public int retryCount;
    public long updatedAt;

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
        this.sourceType = "";
        this.selectedHeight = 0;
        this.selectedFormatId = "";
        this.resolvedMediaUrl = "";
        this.manifestUrl = "";
        this.resumeFilePath = "";
        this.totalBytes = 0L;
        this.downloadedBytes = 0L;
        this.etag = "";
        this.lastModified = "";
        this.contentLength = 0L;
        this.acceptRanges = false;
        this.resourceIdentity = "";
        this.parseStage = "";
        this.parseVersion = 1;
        this.retryCount = 0;
        this.updatedAt = createdAt;
    }
}
