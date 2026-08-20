package com.vi.pl.videoplayerdemo.download;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {DownloadJob.class, DownloadSegment.class}, version = 2, exportSchema = false)
public abstract class DownloadDatabase extends RoomDatabase {
    public abstract DownloadJobDao jobs();
    public abstract DownloadSegmentDao segments();
    private static volatile DownloadDatabase instance;

    public static DownloadDatabase get(Context context) {
        if (instance == null) synchronized (DownloadDatabase.class) {
            if (instance == null) instance = Room.databaseBuilder(
                    context.getApplicationContext(), DownloadDatabase.class, "video-downloads.db")
                    .addMigrations(MIGRATION_1_2).build();
        }
        return instance;
    }

    private static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE download_jobs ADD COLUMN sourceType TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE download_jobs ADD COLUMN selectedHeight INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE download_jobs ADD COLUMN selectedFormatId TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE download_jobs ADD COLUMN resolvedMediaUrl TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE download_jobs ADD COLUMN manifestUrl TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE download_jobs ADD COLUMN resumeFilePath TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE download_jobs ADD COLUMN totalBytes INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE download_jobs ADD COLUMN downloadedBytes INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE download_jobs ADD COLUMN etag TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE download_jobs ADD COLUMN lastModified TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE download_jobs ADD COLUMN contentLength INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE download_jobs ADD COLUMN acceptRanges INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE download_jobs ADD COLUMN resourceIdentity TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE download_jobs ADD COLUMN parseStage TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE download_jobs ADD COLUMN parseVersion INTEGER NOT NULL DEFAULT 1");
            db.execSQL("ALTER TABLE download_jobs ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE download_jobs ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0");
            db.execSQL("CREATE TABLE IF NOT EXISTS `download_segments` (`jobId` TEXT NOT NULL, `streamKind` TEXT NOT NULL, `segmentIndex` INTEGER NOT NULL, `url` TEXT NOT NULL, `byteRange` TEXT, `durationMs` INTEGER NOT NULL, `localPath` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `completed` INTEGER NOT NULL, `checksum` TEXT, PRIMARY KEY(`jobId`, `streamKind`, `segmentIndex`))");
        }
    };
}
