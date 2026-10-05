package dev.mateuy.panoptes.di

import dev.mateuy.panoptes.adapter.appstore.AppStoreAdapter
import dev.mateuy.panoptes.adapter.dmg.DmgAdapter
import dev.mateuy.panoptes.adapter.googleplay.GooglePlayAdapter
import dev.mateuy.panoptes.adapter.microsoft.MicrosoftAdapter
import dev.mateuy.panoptes.adapter.snap.SnapAdapter
import dev.mateuy.panoptes.application.PromoteBuildUseCase
import dev.mateuy.panoptes.application.ViewVersionsUseCase
import dev.mateuy.panoptes.domain.port.CredentialStore
import dev.mateuy.panoptes.domain.port.StoreAdapter
import dev.mateuy.panoptes.infrastructure.ConfigReader
import dev.mateuy.panoptes.infrastructure.HttpClientFactory
import dev.mateuy.panoptes.infrastructure.Project
import org.koin.dsl.module

/** [credentialStore] is created (and unlocked) by the caller: it comes from either the `.env` or `credentials.enc`. */
fun appModule(project: Project, credentialStore: CredentialStore) = module {
    single { HttpClientFactory.create() }
    single { project }
    single<CredentialStore> { credentialStore }
    single { ConfigReader(project.configFile) }

    single<StoreAdapter>(qualifier = org.koin.core.qualifier.named("googleplay")) {
        GooglePlayAdapter(get(), get(), get())
    }
    single<StoreAdapter>(qualifier = org.koin.core.qualifier.named("appstore")) {
        AppStoreAdapter(get(), get(), get())
    }
    single<StoreAdapter>(qualifier = org.koin.core.qualifier.named("microsoft")) {
        MicrosoftAdapter(get(), get(), get())
    }
    single<StoreAdapter>(qualifier = org.koin.core.qualifier.named("snap")) {
        SnapAdapter(get(), get(), get())
    }
    single<StoreAdapter>(qualifier = org.koin.core.qualifier.named("dmg")) {
        DmgAdapter(get(), get())
    }

    single {
        listOf(
            get<StoreAdapter>(qualifier = org.koin.core.qualifier.named("googleplay")),
            get<StoreAdapter>(qualifier = org.koin.core.qualifier.named("appstore")),
            get<StoreAdapter>(qualifier = org.koin.core.qualifier.named("microsoft")),
            get<StoreAdapter>(qualifier = org.koin.core.qualifier.named("snap")),
            get<StoreAdapter>(qualifier = org.koin.core.qualifier.named("dmg")),
        )
    }

    single { ViewVersionsUseCase(get()) }
    single { PromoteBuildUseCase(get()) }
}
