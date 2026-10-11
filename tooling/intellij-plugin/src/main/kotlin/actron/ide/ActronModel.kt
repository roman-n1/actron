package actron.ide

import com.intellij.json.psi.*
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import java.io.File

internal object ActronModel {
    fun withRoot(file: JsonFile, accept: (JsonObject) -> Unit): Boolean {
        if (!file.name.endsWith(".actron.json")) return false
        val root = file.topLevelValue as? JsonObject ?: return false
        if (root.findProperty("format")?.value?.text != "1" || root.findProperty("model")?.value !is JsonObject) return false
        accept(root)
        return true
    }
    fun withModel(file: JsonFile, accept: (JsonObject) -> Unit): Boolean = withRoot(file) { root ->
        accept(requireNotNull(root.findProperty("model")).value as JsonObject)
    }
    fun objects(model: JsonObject, name: String): List<JsonObject> =
        ((model.findProperty(name)?.value as? JsonArray)?.valueList.orEmpty()).filterIsInstance<JsonObject>()
    fun text(value: JsonObject, key: String, accept: (String) -> Unit): Boolean {
        val literal = value.findProperty(key)?.value as? JsonStringLiteral ?: return false
        accept(literal.value)
        return true
    }
    /** All matching declarations, retained in export order for polyvariant PSI resolution. */
    fun declarations(file: JsonFile, key: String): List<JsonStringLiteral> = buildList {
        withModel(file) { model ->
            val list = if (key.startsWith("transition:")) objects(model, "transitions") else objects(model, "states")
            val id = key.removePrefix("state:")
            for (entry in list) {
                val declaration = entry.findProperty("id")?.value as? JsonStringLiteral ?: continue
                if (declaration.value == id) add(declaration)
            }
        }
    }
    fun declarationKey(element: PsiElement, accept: (String) -> Unit): Boolean {
        val literal = element as? JsonStringLiteral ?: return false
        val property = literal.parent as? JsonProperty ?: return false
        if (property.name != "id" || property.value != literal) return false
        val file = literal.containingFile as? JsonFile ?: return false
        val kind = ((property.parent as? JsonObject)?.parent as? JsonArray)?.parent as? JsonProperty ?: return false
        val key = if (kind.name == "transitions") literal.value else "state:${literal.value}"
        if (element !in declarations(file, key)) return false
        accept(key)
        return true
    }
    /** Source data cannot navigate outside the project, including through symlinks. */
    fun source(project: Project, file: JsonFile, key: String, accept: (OpenFileDescriptor) -> Unit): Boolean {
        var navigable = false
        withRoot(file) { root ->
            val source = (root.findProperty("sources")?.value as? JsonObject)?.findProperty(key)?.value as? JsonObject ?: return@withRoot
            text(source, "path") path@ { path ->
                val line = source.findProperty("line")?.value?.text?.toIntOrNull()?.takeIf { it > 0 } ?: return@path
                if (path.isBlank() || path.any { it < ' ' } || path.startsWith('/') || path.contains(':') || path.contains('\\') || path.split('/').any { it == ".." || it == "." || it.isBlank() }) return@path
                val basePath = project.basePath ?: return@path
                val base = runCatching { checkNotNull(File(basePath).canonicalFile) }.getOrNull() ?: return@path
                val target = runCatching { checkNotNull(File(base, path).canonicalFile) }.getOrNull() ?: return@path
                if (!target.toPath().startsWith(base.toPath())) return@path
                val virtual = LocalFileSystem.getInstance().findFileByIoFile(target) ?: return@path
                accept(OpenFileDescriptor(project, virtual, line - 1, 0))
                navigable = true
            }
        }
        return navigable
    }
}
