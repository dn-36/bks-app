import com.bkc.core.domain.repository.ProjectsRepository

class ObserveProjectsUseCase(
    private val repository: ProjectsRepository
) {
    operator fun invoke() = repository.observeProjects()
}