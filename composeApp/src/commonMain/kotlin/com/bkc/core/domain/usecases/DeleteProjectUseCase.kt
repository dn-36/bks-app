import com.bkc.core.domain.Project
import com.bkc.core.domain.repository.ProjectsRepository

class DeleteProjectUseCase(
    private val repository: ProjectsRepository
) {
    suspend operator fun invoke(project: Project) {
        repository.deleteProject(project)
    }
}