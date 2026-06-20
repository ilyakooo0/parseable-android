package com.parseable.android.di

import android.content.Context
import androidx.room.Room
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

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ParseableDatabase =
        Room.databaseBuilder(context, ParseableDatabase::class.java, "parseable.db")
            // Favorites are a rebuildable convenience cache, so a destructive fallback is an
            // acceptable last resort. IMPORTANT: any future schema bump that should PRESERVE
            // favorites must register a real Migration via .addMigrations(...) — otherwise this
            // fallback will silently drop the table on upgrade. Exported schemas (see
            // ParseableDatabase, exportSchema = true) provide the diffs needed to write them.
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
