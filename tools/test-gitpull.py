"""Exercise gitpull against disposable local remotes, without network or user repos."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().with_name("gitpull.zsh")


class GitPullTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="gitpull-test-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.env = dict(os.environ, GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1",
                        GIT_TERMINAL_PROMPT="0", GIT_EDITOR="true", GIT_SEQUENCE_EDITOR="true")
        self.remote = self.root / "origin.git"
        self.seed = self.root / "upstream"
        self.repo = self.root / "local repo"
        self.git(self.root, "init", "--bare", "--initial-branch=main", str(self.remote))
        self.git(self.root, "clone", str(self.remote), str(self.seed))
        self.identity(self.seed)
        for name in ("shared.txt", "local.txt", "staged.txt"):
            (self.seed / name).write_text("base\n")
        self.commit(self.seed, "Initial")
        self.git(self.seed, "push", "origin", "main")
        self.git(self.root, "clone", str(self.remote), str(self.repo))
        self.identity(self.repo)

    def git(self, repo, *args):
        result = subprocess.run(["git", "-C", str(repo), *args], env=self.env,
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result.stdout.strip()

    def identity(self, repo):
        self.git(repo, "config", "user.name", "Gitpull Test")
        self.git(repo, "config", "user.email", "gitpull@example.invalid")

    def commit(self, repo, message):
        self.git(repo, "add", "--all")
        self.git(repo, "commit", "-m", message)
        return self.git(repo, "rev-parse", "HEAD")

    def upstream(self, name="remote.txt", content="upstream\n"):
        (self.seed / name).write_text(content)
        self.commit(self.seed, "Remote change")
        self.git(self.seed, "push", "origin", "main")

    def dirty(self):
        (self.repo / "local.txt").write_text("unstaged\n")
        (self.repo / "staged.txt").write_text("staged\n")
        self.git(self.repo, "add", "staged.txt")
        (self.repo / "new notes.txt").write_text("untracked\n")
        return self.git(self.repo, "status", "--porcelain")

    def pull(self, cloud=False, success=True, public_repos=None):
        command = 'source "$1"; _gitpull_main "$2" "$3"'
        args = [str(self.repo), str(int(cloud))]
        if public_repos is not None:
            command = 'source "$1"; shift; gitpull "$@"'
            args = list(map(str, public_repos))
        result = subprocess.run(["zsh", "-f", "-c", command, "gitpull-test", str(SCRIPT), *args],
                                cwd=self.root, env=self.env, capture_output=True, text=True)
        if success:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def test_clean_update(self):
        self.upstream()
        self.pull()
        self.assertEqual(self.git(self.repo, "rev-parse", "HEAD"),
                         self.git(self.repo, "rev-parse", "origin/main"))

    def test_staged_unstaged_and_untracked_are_restored(self):
        before = self.dirty()
        self.upstream()
        self.pull()
        self.assertEqual(self.git(self.repo, "status", "--porcelain"), before)
        self.assertEqual((self.repo / "new notes.txt").read_text(), "untracked\n")
        self.assertEqual(self.git(self.repo, "show", ":staged.txt"), "staged")
        self.assertEqual((self.repo / "local.txt").read_text(), "unstaged\n")
        self.assertEqual(self.git(self.repo, "stash", "list"), "")

    def test_local_commits_are_rebased(self):
        (self.repo / "mine.txt").write_text("mine\n")
        self.commit(self.repo, "Local change")
        self.upstream()
        self.pull()
        self.git(self.repo, "merge-base", "--is-ancestor", "origin/main", "HEAD")
        self.assertEqual((self.repo / "mine.txt").read_text(), "mine\n")

    def test_existing_stash_is_not_removed(self):
        (self.repo / "local.txt").write_text("older draft\n")
        self.git(self.repo, "stash", "push", "-m", "Keep my stash")
        old_stash = self.git(self.repo, "rev-parse", "refs/stash")
        before = self.dirty()
        self.upstream()
        self.pull()
        self.assertEqual(self.git(self.repo, "rev-parse", "refs/stash"), old_stash)
        self.assertEqual(self.git(self.repo, "show", "stash@{0}:local.txt"), "older draft")
        self.assertEqual(self.git(self.repo, "status", "--porcelain"), before)

    def test_rebase_conflict_restores_commits_and_dirty_files(self):
        (self.repo / "shared.txt").write_text("local commit\n")
        before_head = self.commit(self.repo, "Local conflict")
        before_status = self.dirty()
        self.upstream("shared.txt", "remote conflict\n")
        self.pull(success=False)
        self.assertEqual(self.git(self.repo, "rev-parse", "HEAD"), before_head)
        self.assertEqual(self.git(self.repo, "status", "--porcelain"), before_status)
        self.assertEqual((self.repo / "shared.txt").read_text(), "local commit\n")
        self.assertFalse((self.repo / ".git/rebase-merge").exists())
        self.assertEqual(self.git(self.repo, "stash", "list"), "")

    def test_failed_fetch_restores_dirty_files(self):
        before = self.dirty()
        self.git(self.repo, "remote", "set-url", "origin", str(self.root / "missing.git"))
        self.pull(success=False)
        self.assertEqual(self.git(self.repo, "status", "--porcelain"), before)
        self.assertEqual((self.repo / "new notes.txt").read_text(), "untracked\n")
        self.assertEqual(self.git(self.repo, "stash", "list"), "")

    def test_untracked_collision_keeps_backup(self):
        (self.repo / "remote.txt").write_text("local new file\n")
        self.upstream()
        self.pull(success=False)
        self.assertEqual(self.git(self.repo, "show", "stash@{0}^3:remote.txt"), "local new file")
        self.assertEqual((self.repo / "remote.txt").read_text(), "upstream\n")

    def test_dirty_conflict_keeps_backup(self):
        (self.repo / "shared.txt").write_text("local edits\n")
        self.upstream("shared.txt", "remote edits\n")
        self.pull(success=False)
        self.assertEqual(self.git(self.repo, "show", "stash@{0}:shared.txt"), "local edits")
        self.assertIn("UU shared.txt", self.git(self.repo, "status", "--porcelain"))

    def test_non_main_branch_is_untouched(self):
        self.git(self.repo, "switch", "-c", "feature")
        before = self.dirty()
        self.pull(success=False)
        self.assertEqual(self.git(self.repo, "branch", "--show-current"), "feature")
        self.assertEqual(self.git(self.repo, "status", "--porcelain"), before)
        self.assertEqual(self.git(self.repo, "stash", "list"), "")

    def test_in_progress_operation_is_untouched_from_another_directory(self):
        marker = self.repo / ".git/rebase-merge"
        marker.mkdir()
        self.pull(success=False)
        self.assertTrue(marker.exists())

    def test_cloud_patch_is_imported_once(self):
        self.git(self.seed, "switch", "-c", "ccr-test")
        (self.seed / "cloud.txt").write_text("cloud\n")
        self.commit(self.seed, "Cloud change")
        self.git(self.seed, "push", "origin", "ccr-test")
        self.git(self.seed, "switch", "main")
        before = self.dirty()
        self.pull(cloud=True)
        head = self.git(self.repo, "rev-parse", "HEAD")
        self.assertEqual((self.repo / "cloud.txt").read_text(), "cloud\n")
        self.assertEqual(self.git(self.repo, "status", "--porcelain"), before)
        self.pull(cloud=True)
        self.assertEqual(self.git(self.repo, "rev-parse", "HEAD"), head)
        self.assertEqual(self.git(self.repo, "status", "--porcelain"), before)

    def test_cloud_conflict_aborts_and_restores_dirty_files(self):
        self.git(self.seed, "switch", "-c", "ccr-conflict")
        (self.seed / "shared.txt").write_text("cloud conflict\n")
        self.commit(self.seed, "Cloud conflict")
        self.git(self.seed, "push", "origin", "ccr-conflict")
        self.git(self.seed, "switch", "main")
        (self.repo / "shared.txt").write_text("local commit\n")
        before_head = self.commit(self.repo, "Local conflict")
        before = self.dirty()
        self.pull(cloud=True, success=False)
        self.assertEqual(self.git(self.repo, "rev-parse", "HEAD"), before_head)
        self.assertEqual(self.git(self.repo, "status", "--porcelain"), before)
        self.assertFalse((self.repo / ".git/CHERRY_PICK_HEAD").exists())
        self.assertEqual(self.git(self.repo, "stash", "list"), "")

    def test_same_subject_and_author_time_do_not_hide_another_patch(self):
        initial = self.git(self.seed, "rev-parse", "HEAD")
        self.env.update(GIT_AUTHOR_DATE="2026-10-29T10:00:00+03:00",
                        GIT_COMMITTER_DATE="2026-10-29T10:00:00+03:00")
        (self.seed / "main.txt").write_text("main\n")
        self.commit(self.seed, "Same subject")
        self.git(self.seed, "push", "origin", "main")
        self.git(self.seed, "switch", "-c", "ccr-other", initial)
        (self.seed / "cloud.txt").write_text("different patch\n")
        self.commit(self.seed, "Same subject")
        self.git(self.seed, "push", "origin", "ccr-other")
        self.pull(cloud=True)
        self.assertEqual((self.repo / "main.txt").read_text(), "main\n")
        self.assertEqual((self.repo / "cloud.txt").read_text(), "different patch\n")

    def test_one_failed_repository_does_not_prevent_the_next_update(self):
        self.upstream()
        self.pull(public_repos=[self.root / "missing", self.repo], success=False)
        self.assertEqual((self.repo / "remote.txt").read_text(), "upstream\n")


if __name__ == "__main__":
    unittest.main(verbosity=2)
