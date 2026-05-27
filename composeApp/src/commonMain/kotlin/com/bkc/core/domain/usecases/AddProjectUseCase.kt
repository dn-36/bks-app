import com.bkc.core.domain.repository.ProjectsRepository

class AddProjectUseCase(
    private val repository: ProjectsRepository
) {
    suspend operator fun invoke(title: String, fileName: String, file: ByteArray) {
        repository.addProject(title, fileName, file)
    }
}
