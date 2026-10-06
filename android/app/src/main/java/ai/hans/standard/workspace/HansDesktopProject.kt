package ai.hans.standard.workspace

import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * One stable task workspace for local Hans and desktop-created phone tasks.
 *
 * This is deliberately the existing private workspace, not Codex's runtime or
 * account directory. Preparing it never enables remote access, changes Android
 * permissions, creates executables, or rewrites existing user project files.
 */
object HansDesktopProject {
    const val DIRECTORY_NAME = "codex-workspace"

    fun ensure(filesDirectory: File): File {
        val project = projectFile(filesDirectory)
        try {
            Files.createDirectory(
                project.toPath(),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
            )
        } catch (_: FileAlreadyExistsException) {
            // An existing project is retained only if it is still a real directory.
        }
        check(isPrepared(project)) { "Could not prepare app-private Hans project" }
        return project.canonicalFile
    }

    /** Fresh, read-only availability; a failed preparation never becomes a UI claim. */
    fun preparedPath(filesDirectory: File): String? = runCatching {
        projectFile(filesDirectory).takeIf(::isPrepared)?.canonicalPath
    }.getOrNull()

    private fun projectFile(filesDirectory: File): File {
        require(filesDirectory.isAbsolute) { "filesDir must be absolute" }
        val parent = filesDirectory.canonicalFile
        check(parent.isDirectory) { "App-private files directory is unavailable" }
        return File(parent, DIRECTORY_NAME)
    }

    private fun isPrepared(project: File): Boolean =
        Files.isDirectory(project.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(project.toPath()) &&
            project.canonicalFile == project.absoluteFile &&
            Files.isReadable(project.toPath()) && Files.isWritable(project.toPath()) &&
            Files.isExecutable(project.toPath())
}
