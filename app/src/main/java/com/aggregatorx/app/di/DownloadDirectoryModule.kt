package com.aggregatorx.app.di

// DownloadManager.downloadDirectory is set at runtime via SettingsViewModel.
// No Hilt module needed — an unqualified String binding would conflict with
// other String parameters in the dependency graph.
