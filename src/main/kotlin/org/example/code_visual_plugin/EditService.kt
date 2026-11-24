package org.example.code_visual_plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * 行级修改 + 版本回滚。
 *
 * 设计约束（来自需求）：
 *   - 每次只改给定的若干行，不整文件重写，避免动到别的地方
 *   - 改之前把整份文本快照下来，最多保留 3 版，供"回滚"
 *   - 所有写入走 WriteCommandAction，用户在编辑器里还能 Ctrl+Z
 *
 * 说明：快照目前存在内存里（随 IDE 重启失效）。
 * 如果需要跨重启保留，可以再落盘到项目 .idea 目录下。
 */
data class EditResult(
    val success: Boolean = true,
    val message: String = "",
    /** 还可以回滚几版 */
    val versions: Int = 0,
    /** 实际改动了几行 */
    val changed: Int = 0
)

object EditService {

    private const val MAX_VERSIONS = 3

    /** 快照落盘目录：<项目>/.idea/codecanvas/edits/<hash>-<文件名>/snap-<时间戳>.txt */
    private fun snapshotDir(project: Project, file: VirtualFile): File? {
        val base = project.basePath ?: return null
        val key = Integer.toHexString(file.path.hashCode()) + "-" + file.name
        val dir = File(base, ".idea/codecanvas/edits/$key")
        return if (dir.exists() || dir.mkdirs()) dir else null
    }

    private fun snapshots(project: Project, file: VirtualFile): List<File> {
        val dir = snapshotDir(project, file) ?: return emptyList()
        val list = dir.listFiles { f -> f.isFile && f.name.startsWith("snap-") } ?: return emptyList()
        return list.sortedByDescending { it.name }
    }

    fun versionCount(project: Project?, moduleId: String, fileName: String): Int {
        if (project == null) return 0
        val file = findFile(moduleId, fileName) ?: return 0
        return snapshots(project, file).size
    }

    /**
     * 把若干行替换成新文本。
     * edits 的 line 是 1-based 的真实行号；text 可以包含换行（相当于插入多行）。
     */
    fun apply(project: Project, moduleId: String, fileName: String, edits: List<Pair<Int, String>>): EditResult {
        if (edits.isEmpty()) return EditResult(success = false, message = "没有要修改的行")

        val file = findFile(moduleId, fileName)
            ?: return EditResult(success = false, message = "找不到文件：$fileName（可能已移动，请刷新）")

        val manager = FileDocumentManager.getInstance()
        // getDocument 必须在读锁里调用，否则会抛
        // "Read access is allowed from inside read-action only"
        val document = documentOf(file)
            ?: return EditResult(success = false, message = "拿不到文档，可能是二进制文件")

        var failure: String? = null
        var changed = 0

        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                try {
                    val snapshot = document.text
                    // 从后往前改，避免前面的改动让后面的行号偏移
                    for ((lineNo, text) in edits.sortedByDescending { it.first }) {
                        val index = lineNo - 1
                        if (index < 0 || index >= document.lineCount) {
                            failure = "第 $lineNo 行超出文件范围"
                            break
                        }
                        val start = document.getLineStartOffset(index)
                        val end = document.getLineEndOffset(index)
                        if (document.getText(com.intellij.openapi.util.TextRange(start, end)) == text) {
                            continue // 已经是目标内容，跳过
                        }
                        document.replaceString(start, end, text.replace("\r\n", "\n"))
                        changed++
                    }

                    if (failure == null) {
                        pushSnapshot(project, file, snapshot)
                        manager.saveDocument(document)
                    }
                } catch (e: Throwable) {
                    failure = e.message ?: e.javaClass.simpleName
                }
            }
        }

        failure?.let { return EditResult(success = false, message = it) }
        if (changed == 0) return EditResult(success = true, message = "内容没有变化", versions = versionCount(project, moduleId, fileName))
        return EditResult(
            success = true,
            message = "已修改 $changed 行",
            versions = versionCount(project, moduleId, fileName),
            changed = changed
        )
    }

    /** 回滚到上一个快照 */
    fun rollback(project: Project, moduleId: String, fileName: String): EditResult {
        val file = findFile(moduleId, fileName)
            ?: return EditResult(success = false, message = "找不到文件：$fileName")

        val manager = FileDocumentManager.getInstance()
        val document = documentOf(file)
            ?: return EditResult(success = false, message = "拿不到文档")

        val snapshot = popSnapshot(project, file)
            ?: return EditResult(success = false, message = "没有可回滚的版本")

        var failure: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                try {
                    document.setText(snapshot)
                    manager.saveDocument(document)
                } catch (e: Throwable) {
                    failure = e.message ?: e.javaClass.simpleName
                }
            }
        }

        failure?.let { return EditResult(success = false, message = it) }
        return EditResult(
            success = true,
            message = "已回滚",
            versions = versionCount(project, moduleId, fileName)
        )
    }

    // ------------------------------------------------------------ 内部

    private fun documentOf(file: VirtualFile): Document? =
        ReadAction.compute<Document?, RuntimeException> {
            FileDocumentManager.getInstance().getDocument(file)
        }

    private fun findFile(moduleId: String, fileName: String): VirtualFile? =
        ReadAction.compute<VirtualFile?, RuntimeException> {
            ModuleFileIndex.filesOf(moduleId).firstOrNull { it.name == fileName }
        }

    /** 改动前把整份文本写一份快照，最多保留 MAX_VERSIONS 份 */
    private fun pushSnapshot(project: Project, file: VirtualFile, snapshot: String) {
        val dir = snapshotDir(project, file) ?: return
        try {
            val name = "snap-" + System.currentTimeMillis() + ".txt"
            File(dir, name).writeText(snapshot, Charsets.UTF_8)
            // 只留最近 N 份
            snapshots(project, file).drop(MAX_VERSIONS).forEach { it.delete() }
        } catch (e: Throwable) {
            // 快照失败不影响写入本身
        }
    }

    /** 取最近一份快照并删除（回滚一步） */
    private fun popSnapshot(project: Project, file: VirtualFile): String? {
        val newest = snapshots(project, file).firstOrNull() ?: return null
        return try {
            val text = newest.readText(Charsets.UTF_8)
            newest.delete()
            text
        } catch (e: Throwable) {
            null
        }
    }
}
