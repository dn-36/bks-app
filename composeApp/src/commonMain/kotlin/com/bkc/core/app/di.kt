package com.bkc.core.app


import AddProjectUseCase
import DeleteProjectUseCase
import ObserveProjectsUseCase
import com.bkc.core.data.MockDiscussionRepository
import com.bkc.core.data.MockObjectRepository
import com.bkc.core.data.MockProjectRepository
import com.bkc.core.data.ServerAccountRepository
import com.bkc.core.data.ServerChatRepository
import com.bkc.core.data.ServerObjectRepository
import com.bkc.core.data.ServerProjectsRepository
import com.bkc.core.data.ServerScheduleRepository
import com.bkc.core.data.ServerShiftTaskRepository
import com.bkc.core.data.ServerSpecificationRepository
import com.bkc.core.data.local_storage.SettingsUserSessionStore
import com.bkc.core.domain.repository.AccountRepository
import com.bkc.core.domain.repository.ChatRepository
import com.bkc.core.domain.repository.DiscussionRepository
import com.bkc.core.domain.repository.GetDiscussions
import com.bkc.core.domain.repository.GetObjects
import com.bkc.core.domain.repository.GetProjects
import com.bkc.core.domain.repository.GetSpecifications
import com.bkc.core.domain.repository.ObjectRepository
import com.bkc.core.domain.repository.ProjectRepository
import com.bkc.core.domain.repository.ProjectsRepository
import com.bkc.core.domain.repository.ScheduleRepository
import com.bkc.core.domain.repository.ShiftTaskRepository
import com.bkc.core.domain.repository.SpecificationRepository
import com.bkc.core.domain.repository.UserSessionStore
import com.bkc.core.presentation.mvi.UiListState
import com.bkc.screens.objects.ObjectsScreenModel
import com.bkc.screens.profile.ProfileScreen
import com.bkc.screens.projects.viewmodel.ProjectsViewModel
import com.bkc.screens.projects_user.ProjectsUserScreenModel
import com.bkc.screens.requests.RequestsScreenModel
import com.bkc.screens.schedules.SchedulesScreenModel
import com.bkc.screens.shit.ShiftTaskScreenModel
import com.bkc.screens.splash.ui.SplashScreen
import com.bkc.screens.splash.viewmodel.SplashScreenModel
import com.bkc.screens.user_login.domain.repository.AuthRepository
import com.bkc.screens.user_login.data.AuthRepositoryImpl
import com.russhwolf.settings.Settings
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module


val   koinModules : Module =
        module {
            single {  }
            single { AppStateStore() }
            single<ProjectsRepository> {
                ServerProjectsRepository(get())
            }
            single<ScheduleRepository> {
                ServerScheduleRepository(get())
            }
            single<ShiftTaskRepository> {
                ServerShiftTaskRepository(get())
            }
            single<AccountRepository> {
                ServerAccountRepository(get())
            }
            single<ChatRepository> {
                ServerChatRepository(get())
            }

            single<ProjectRepository> {
                MockProjectRepository()  as ProjectRepository
            }
            single {  }
            single<ObjectRepository> { ServerObjectRepository(get()) }
            single<DiscussionRepository> { MockDiscussionRepository() }
            single<SpecificationRepository> { ServerSpecificationRepository(get()) }
            factory<ObjectsScreenModel> { ObjectsScreenModel() }
            factory<ProjectsUserScreenModel> { ProjectsUserScreenModel() }
            factory<SchedulesScreenModel> { SchedulesScreenModel() }
            factory<RequestsScreenModel> { RequestsScreenModel() }
            factory<ShiftTaskScreenModel> { ShiftTaskScreenModel() }
            single< SplashScreenModel> {
                SplashScreenModel()
            }
            single<SplashScreen> {
               SplashScreen(ProfileScreen())
            }
            // use cases
            factory { GetProjects(get()) }
            factory { GetObjects(get()) }
            factory { GetDiscussions(get()) }
            factory { GetSpecifications(get()) }
            factory { ObserveProjectsUseCase(get())}
            factory { AddProjectUseCase(get())}
            factory { DeleteProjectUseCase(get())}
            factory {
                ProjectsViewModel(get(),get(),get(),)
            }

            single<AuthRepository> { AuthRepositoryImpl() }
            single { createSettings() }

            single<UserSessionStore> { SettingsUserSessionStore(get()) }
        }




expect object SettingsProvider {
    fun factory(): Settings.Factory
}

fun createSettings(): Settings =
    SettingsProvider.factory().create(name = "app_settings")
