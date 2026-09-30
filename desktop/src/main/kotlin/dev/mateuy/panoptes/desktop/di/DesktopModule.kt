package dev.mateuy.panoptes.desktop.di

import dev.mateuy.panoptes.desktop.dashboard.DashboardViewModel
import dev.mateuy.panoptes.desktop.session.Session
import dev.mateuy.panoptes.desktop.settings.SettingsViewModel
import dev.mateuy.panoptes.desktop.unlock.UnlockViewModel
import dev.mateuy.panoptes.infrastructure.Project
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

fun desktopModule(project: Project) = module {
    single { Session(getKoin(), project) }

    viewModelOf(::UnlockViewModel)
    // Dashboard and settings dependencies come from appModule, loaded after unlocking
    viewModelOf(::DashboardViewModel)
    viewModelOf(::SettingsViewModel)
}
