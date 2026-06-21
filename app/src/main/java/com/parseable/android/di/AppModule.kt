package com.parseable.android.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.parseable.android.data.local.FavoriteStreamDao
import com.parseable.android.data.local.ParseableDatabase
import com.parseable.android.data.local.SavedServerDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * v1 → v2 added the `saved_servers` table (multi-server management). This is NOT a
     * rebuildable cache like favorites: it holds the user's saved server list and the keys
     * into EncryptedSharedPreferences for each server's password. Losing it strands users'
     * credentials, so the upgrade must be a real additive migration, not a destructive wipe.
     *
     * The CREATE TABLE statement must match the schema Room generates for the SavedServer
     * entity exactly (column names, affinities, NOT NULL, the AUTOINCREMENT primary key) or
     * Room's post-migration schema validation will throw on first open.
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `saved_servers` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`serverUrl` TEXT NOT NULL, " +
                    "`username` TEXT NOT NULL, " +
                    "`useTls` INTEGER NOT NULL, " +
                    "`passwordKey` TEXT NOT NULL, " +
                    "`addedAt` INTEGER NOT NULL)"
            )
        }
    }

    /**
     * v2 → v3 adds a UNIQUE index on (serverUrl, username) so the one-row-per-server invariant
     * is enforced by the schema rather than only by app logic. Any pre-existing duplicate rows
     * (possible because v2 had no constraint) must be collapsed first or the index creation
     * fails — keep the lowest id for each (url, username). The index name and SQL must match
     * what Room generates for the entity's @Index or post-migration validation will throw.
     */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "DELETE FROM `saved_servers` WHERE `id` NOT IN " +
                    "(SELECT MIN(`id`) FROM `saved_servers` GROUP BY `serverUrl`, `username`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_saved_servers_serverUrl_username` " +
                    "ON `saved_servers` (`serverUrl`, `username`)"
            )
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ParseableDatabase =
        Room.databaseBuilder(context, ParseableDatabase::class.java, "parseable.db")
            // Register real migrations for any schema bump that must preserve user data.
            // IMPORTANT: every future version bump needs a Migration added here — the
            // destructive fallback below is only a last resort for unmigrated paths and will
            // silently drop ALL tables (including saved_servers) if it ever runs on upgrade.
            // Exported schemas (see ParseableDatabase, exportSchema = true) provide the diffs.
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideFavoriteStreamDao(db: ParseableDatabase): FavoriteStreamDao =
        db.favoriteStreamDao()

    @Provides
    @Singleton
    fun provideSavedServerDao(db: ParseableDatabase): SavedServerDao =
        db.savedServerDao()
}
