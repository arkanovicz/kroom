package com.republicate.kroom.webapp.authoring.git

import com.republicate.kroom.webapp.authoring.Block
import com.republicate.kroom.webapp.authoring.FileResourceStore
import com.republicate.kroom.webapp.authoring.Revision
import com.republicate.kroom.webapp.authoring.Versioned
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffConfig
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.FollowFilter
import org.eclipse.jgit.revwalk.RenameCallback
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.io.DisabledOutputStream
import org.slf4j.LoggerFactory
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.relativeTo

/**
 * The content tree as its own git repository (`root/.git`, not the project's): every write, deletion or move is
 * a commit, the editor's history is the `git log`, and a block that moves keeps its past (renames are followed).
 * A revision is named by its commit id, abbreviated or full.
 *
 * Content already under [root] when the repository is created becomes its first commit. The file is written
 * before git is asked: a commit that fails is logged and costs nothing but the history line.
 *
 * Commits are authored by the block's author (`meta["author"]`, else the last one, else [committer]) with
 * `<author>@<domain>` as address, and committed by [committer].
 */
class GitResourceStore(
    private val root: Path,
    private val committer: String = "kroom",
    private val domain: String = "kroom.local",
) : FileResourceStore(root), Versioned {

    private val log = LoggerFactory.getLogger(GitResourceStore::class.java)

    private val git: Git = Files.createDirectories(root).let {
        if (Files.isDirectory(root.resolve(".git"))) Git.open(root.toFile())
        else Git.init().setDirectory(root.toFile()).call().also { git ->
            if (list().isNotEmpty()) {
                git.add().addFilepattern(".").call()
                commit(git, "initial content", committer)
            }
        }
    }

    private val repository get() = git.repository

    // rename detection for the follow walks, whatever the repository's own config says
    private val diffConfig: DiffConfig = Config().apply { setBoolean("diff", null, "renames", true) }.get(DiffConfig.KEY)

    @Synchronized
    override fun write(path: String, body: String, meta: Map<String, String>): Block {
        guard(path)
        val author = meta["author"] ?: read(path)?.author ?: committer
        val block = super.write(path, body, meta)
        attempt("commit of $path") {
            if (git.status().addPath(path).call().isClean) return@attempt
            git.add().addFilepattern(path).call()
            commit(git, meta["source"]?.let { "$path\n\nsource $it" } ?: path, author)
        }
        return block
    }

    @Synchronized
    override fun delete(path: String): Boolean {
        guard(path)
        if (!super.delete(path)) return false
        attempt("deletion of $path") {
            git.rm().addFilepattern(path).call()
            commitIfStaged("$path (deleted)", path)
        }
        return true
    }

    @Synchronized
    override fun move(from: String, to: String): Boolean {
        guard(from)
        guard(to)
        if (!super.move(from, to)) return false
        attempt("move of $from") {
            git.rm().setCached(true).addFilepattern(from).call()
            git.add().addFilepattern(to).call()
            commitIfStaged("moved from $from", from, to)
        }
        return true
    }

    /** The content's files, without the repository's own. */
    override fun list(prefix: String): List<String> {
        if (!Files.isDirectory(root)) return emptyList()
        val paths = ArrayList<String>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes) =
                if (dir.fileName?.toString() == ".git" && dir != root) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) file.relativeTo(root).invariantSeparatorsPathString.takeIf { it.startsWith(prefix) }?.let { paths.add(it) }
                return FileVisitResult.CONTINUE
            }
        })
        return paths.sorted()
    }

    @Synchronized
    override fun log(path: String?, limit: Int): List<Revision> {
        if (repository.resolve(Constants.HEAD) == null) return emptyList()
        if (path == null) {
            return git.log().setMaxCount(limit).call().map { commit ->
                revision(commit, firstPath(commit) ?: "")
            }
        }
        return follow(path).asSequence().take(limit).map { (commit, name) -> revision(commit, name) }.toList()
    }

    @Synchronized
    override fun read(path: String, rev: String): Block? {
        if (!rev.matches(HEX)) return null
        return try {
            val id = repository.resolve("$rev^{commit}") ?: return null
            // the name the block had at that commit, when the commit is in its history; else its current one
            val name = follow(path).firstOrNull { (commit, _) -> commit.id == id }?.second ?: path
            RevWalk(repository).use { walk -> blob(walk.parseCommit(id), name) }
        } catch (e: Exception) {
            null // ambiguous or unknown revision
        }
    }

    private fun blob(commit: RevCommit, name: String): Block? {
        val blob = TreeWalk.forPath(repository, name, commit.tree)?.use { it.getObjectId(0) } ?: return null
        return parse(String(repository.open(blob).bytes, Charsets.UTF_8), name)
    }

    /**
     * The commits that touched [path], newest first, each with the name the block bore in it — renames followed,
     * so a moved block's history goes on under its new name.
     */
    private fun follow(path: String): List<Pair<RevCommit, String>> {
        val head = repository.resolve(Constants.HEAD) ?: return emptyList()
        val names = ArrayList<String>().apply { add(path) }
        val found = ArrayList<Pair<RevCommit, String>>()
        RevWalk(repository).use { walk ->
            walk.markStart(walk.parseCommit(head))
            walk.treeFilter = FollowFilter.create(path, diffConfig).apply {
                renameCallback = object : RenameCallback() {
                    override fun renamed(entry: DiffEntry) { names.add(entry.oldPath) }
                }
            }
            for (commit in walk) {
                // the newest of the names present in this commit's tree
                val name = names.firstOrNull { TreeWalk.forPath(repository, it, commit.tree)?.use { true } == true } ?: continue
                found.add(commit to name)
            }
        }
        return found
    }

    private fun revision(commit: RevCommit, path: String) = Revision(
        rev = commit.abbreviate(12).name(),
        path = path,
        author = commit.authorIdent.name,
        time = commit.commitTime * 1000L,
        message = commit.shortMessage,
    )

    /** The path a commit touches, for the journal: a move's destination, a deletion's path. */
    private fun firstPath(commit: RevCommit): String? =
        DiffFormatter(DisabledOutputStream.INSTANCE).use { diff ->
            diff.setRepository(repository)
            diff.isDetectRenames = true
            diff.scan(commit.parents.firstOrNull()?.tree, commit.tree).firstOrNull()
                ?.let { if (it.changeType == DiffEntry.ChangeType.DELETE) it.oldPath else it.newPath }
        }

    private fun commitIfStaged(message: String, vararg paths: String) {
        val status = git.status().apply { paths.forEach { addPath(it) } }.call()
        if (status.added.isEmpty() && status.changed.isEmpty() && status.removed.isEmpty()) return
        commit(git, message, committer)
    }

    private fun commit(git: Git, message: String, author: String) {
        val address = author.replace(Regex("[^A-Za-z0-9._-]"), "_") + "@" + domain
        git.commit()
            .setMessage(message)
            .setAuthor(PersonIdent(author, address))
            .setCommitter(PersonIdent(committer, "$committer@$domain"))
            .setSign(false)
            .setNoVerify(true)
            .call()
    }

    private inline fun attempt(what: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            log.warn("git, $what failed in $root (the file is written, the commit is missing)", e)
        }
    }

    override fun read(path: String): Block? = if (inRepository(path)) null else super.read(path)

    /** The repository is not content. */
    private fun guard(path: String) = require(!inRepository(path)) { "path inside the content repository: '$path'" }

    private fun inRepository(path: String) = path.split('/').any { it == ".git" }

    private companion object {
        val HEX = Regex("[0-9a-fA-F]{4,40}")
    }
}
