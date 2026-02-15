package com.parseable.android.di

import android.content.Context
import androidx.room.Room
import com.parseable.android.data.local.FavoriteStreamDao
import com.parseable.android.data.local.ParseableDatabase
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
            .build()

    @Provides
    fun provideFavoriteStreamDao(db: ParseableDatabase): FavoriteStreamDao =
        db.favoriteStreamDao()
}
