package com.vi.pl.videoplayerdemo.download;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {DownloadJob.class}, version = 1, exportSchema = false)
public abstract class DownloadDatabase extends RoomDatabase {
    public abstract DownloadJobDao jobs();
    private static volatile DownloadDatabase instance;

    public static DownloadDatabase get(Context context) {
        if (instance == null) synchronized (DownloadDatabase.class) {
            if (instance == null) instance = Room.databaseBuilder(
                    context.getApplicationContext(), DownloadDatabase.class, "video-downloads.db").build();
        }
        return instance;
    }
}
