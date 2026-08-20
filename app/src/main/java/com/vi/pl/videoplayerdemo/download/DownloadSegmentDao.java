package com.vi.pl.videoplayerdemo.download;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import java.util.List;

@Dao
public interface DownloadSegmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) void insertAll(List<DownloadSegment> segments);
    @Update void update(DownloadSegment segment);
    @Query("SELECT * FROM download_segments WHERE jobId = :jobId ORDER BY streamKind, segmentIndex") List<DownloadSegment> forJob(String jobId);
    @Query("SELECT * FROM download_segments WHERE jobId = :jobId AND completed = 0 ORDER BY streamKind, segmentIndex") List<DownloadSegment> pending(String jobId);
    @Query("DELETE FROM download_segments WHERE jobId = :jobId") void deleteForJob(String jobId);
}
