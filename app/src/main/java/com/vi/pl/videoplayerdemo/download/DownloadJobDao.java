package com.vi.pl.videoplayerdemo.download;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import java.util.List;

@Dao
public interface DownloadJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) void insert(DownloadJob job);
    @Update void update(DownloadJob job);
    @Query("SELECT * FROM download_jobs ORDER BY createdAt DESC") List<DownloadJob> all();
    @Query("SELECT * FROM download_jobs WHERE id = :id LIMIT 1") DownloadJob find(String id);
    @Query("DELETE FROM download_jobs WHERE id = :id") void delete(String id);
}
