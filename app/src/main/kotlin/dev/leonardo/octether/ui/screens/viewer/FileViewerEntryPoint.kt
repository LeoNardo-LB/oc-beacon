package dev.leonardo.octether.ui.screens.viewer

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FileViewerEntryPoint {
    fun fileViewerViewModelFactory(): FileViewerViewModel.Factory
}
