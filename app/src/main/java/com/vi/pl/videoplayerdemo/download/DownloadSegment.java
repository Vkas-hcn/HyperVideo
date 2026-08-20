package com.vi.pl.videoplayerdemo.download;

import androidx.annotation.NonNull;
import androidx.room.Entity;

/** Persisted HLS/DASH unit.  A completed file is never requested again on resume. */
@Entity(tableName = "download_segments", primaryKeys = {"jobId", "streamKind", "segmentIndex"})
public class DownloadSegment {
    @NonNull public String jobId;
    @NonNull public String streamKind;
    public long segmentIndex;
    @NonNull public String url;
    public String byteRange;
    public long durationMs;
    @NonNull public String localPath;
    public long sizeBytes;
    public boolean completed;
    public String checksum;

    public DownloadSegment(@NonNull String jobId, @NonNull String streamKind, long segmentIndex,
                           @NonNull String url, String byteRange, long durationMs,
                           @NonNull String localPath, long sizeBytes, boolean completed, String checksum) {
        this.jobId = jobId;
        this.streamKind = streamKind;
        this.segmentIndex = segmentIndex;
        this.url = url;
        this.byteRange = byteRange;
        this.durationMs = durationMs;
        this.localPath = localPath;
        this.sizeBytes = sizeBytes;
        this.completed = completed;
        this.checksum = checksum;
    }
}
