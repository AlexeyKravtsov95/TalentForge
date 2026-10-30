"""The deduplication state as one small git branch: download, commit, push, rebase.

The branch ``telegram-notify-state`` holds the three state documents of
:mod:`talent_data.notify.state` and nothing else. It is the durable store the task asks
for: not the Actions cache (evicted), not run artifacts (retention), but the repository
itself - visible, diffable and restored by a plain ``git fetch``.

The token authenticates git the way ``actions/checkout`` does (an ``http.<server>``
``extraheader`` passed through the environment, never through the command line or the
remote URL), reusing :func:`talent_data.prepare.propose.git_auth`. A push that loses a
race (another producer committed first) is retried by fetching and rebasing onto the new
tip - the producers write disjoint files, so the rebase never conflicts in practice;
a genuinely conflicting one raises and the next scheduled run recovers. The bootstrap
race (two producers creating the branch at once) can also raise; the next run then finds
the branch and continues on it.
"""

from __future__ import annotations

import os
import subprocess
from pathlib import Path

from talent_data.prepare.propose import git_auth

STATE_BRANCH = "telegram-notify-state"
#: pushes that lose a race are rebased and retried at most this many times
PUSH_ATTEMPTS = 3
_AUTHOR_NAME = "talentforge-notify"
_AUTHOR_EMAIL = "actions@github.com"


class GitStateError(RuntimeError):
    """The state branch could not be read or written. Git's own stderr is included; it
    never contains the token (the token travels in the environment only)."""


def _git(cwd: Path, env: dict[str, str], *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", str(cwd), *args],
        capture_output=True,
        text=True,
        env={**os.environ, **env},
        check=False,
    )


def _ok(cwd: Path, env: dict[str, str], what: str, *args: str) -> subprocess.CompletedProcess[str]:
    result = _git(cwd, env, *args)
    if result.returncode != 0:
        raise GitStateError(f"git {' '.join(args)} failed ({what}): {result.stderr.strip()}")
    return result


def download(
    state_dir: Path,
    *,
    repository: str,
    server_url: str,
    branch: str = STATE_BRANCH,
    token: str,
) -> bool:
    """Make ``state_dir`` a git repository holding the state branch's files (``True``)
    or an empty repository ready to create it (``False``, the first run ever).

    A network failure is never mistaken for an absent branch: when the fetch fails but
    ``ls-remote`` still sees the ref, :class:`GitStateError` is raised - quietly
    re-baselining could replay an old event."""
    url = f"{server_url.rstrip('/')}/{repository}.git"
    env = git_auth(token, server_url)
    state_dir.mkdir(parents=True, exist_ok=True)
    _ok(state_dir, env, "init", "init", str(state_dir))
    _ok(state_dir, env, "remote", "remote", "add", "origin", url)
    fetch = _git(state_dir, env, "fetch", "--no-tags", "origin", branch)
    if fetch.returncode == 0:
        _ok(state_dir, env, "checkout", "checkout", "-B", branch, "FETCH_HEAD")
        return True
    remote = _git(state_dir, env, "ls-remote", "origin", f"refs/heads/{branch}")
    if remote.returncode != 0:
        raise GitStateError(
            f"the state branch {branch} could neither be fetched nor probed: "
            f"{fetch.stderr.strip()}; {remote.stderr.strip()}"
        )
    if remote.stdout.strip():
        raise GitStateError(
            f"the state branch {branch} exists but could not be fetched: {fetch.stderr.strip()}"
        )
    _ok(state_dir, env, "checkout", "checkout", "-B", branch)
    return False


def upload(
    state_dir: Path,
    *,
    branch: str = STATE_BRANCH,
    token: str,
    server_url: str,
    message: str,
) -> bool:
    """Commit the state files and push. ``False`` when there was nothing to change."""
    env = git_auth(token, server_url)
    _ok(state_dir, env, "add", "add", "-A")
    if _git(state_dir, env, "diff", "--cached", "--quiet").returncode == 0:
        return False
    _ok(
        state_dir,
        env,
        "commit",
        "-c",
        f"user.name={_AUTHOR_NAME}",
        "-c",
        f"user.email={_AUTHOR_EMAIL}",
        "commit",
        "--author",
        f"{_AUTHOR_NAME} <{_AUTHOR_EMAIL}>",
        "-m",
        message,
    )
    for attempt in range(1, PUSH_ATTEMPTS + 1):
        push = _git(state_dir, env, "push", "origin", f"HEAD:refs/heads/{branch}")
        if push.returncode == 0:
            return True
        # someone else committed first: move on top of the new tip and try again
        fetch = _git(state_dir, env, "fetch", "--no-tags", "origin", branch)
        if fetch.returncode != 0:
            raise GitStateError(
                f"push attempt {attempt} failed and the new tip could not be fetched: "
                f"{push.stderr.strip()}; {fetch.stderr.strip()}"
            )
        rebase = _git(state_dir, env, "rebase", "FETCH_HEAD")
        if rebase.returncode != 0:
            _git(state_dir, env, "rebase", "--abort")
            raise GitStateError(
                f"push attempt {attempt} failed and the rebase onto the new tip "
                f"conflicted: {push.stderr.strip()}"
            )
    raise GitStateError(
        f"the state branch {branch} could not be pushed after {PUSH_ATTEMPTS} attempts"
    )
