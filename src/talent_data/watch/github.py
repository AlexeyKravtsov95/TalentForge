"""Minimal GitHub Issues REST client for the freshness watcher.

Only what the watcher needs: list open issues, create, edit, comment, close. Every call
authenticates with the workflow's ``GITHUB_TOKEN``; any non-2xx answer, transport error or
unexpected response shape raises :class:`GitHubIssuesError` - an undelivered notification
is never reported as delivered. No retries: the next scheduled run retries.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import httpx

API_URL = "https://api.github.com"
API_VERSION = "2022-11-28"
_PAGE_SIZE = 100
_MAX_PAGES = 50

_HINTS = {
    401: "the token was rejected (is GITHUB_TOKEN passed to the step?)",
    403: (
        "access denied: the workflow needs `permissions: issues: write` and the token must "
        "be allowed to write issues (fork pull requests and read-only tokens cannot)"
    ),
    404: "repository not found, or Issues are disabled / not accessible with this token",
    410: "Issues are disabled for this repository (Settings → General → Features → Issues)",
}


class GitHubIssuesError(RuntimeError):
    """A GitHub Issues call failed; ``status_code`` is ``None`` for transport errors."""

    def __init__(self, message: str, *, status_code: int | None = None) -> None:
        super().__init__(message)
        self.status_code = status_code


@dataclass(frozen=True)
class Issue:
    number: int
    title: str
    body: str
    html_url: str


class GitHubIssues:
    def __init__(
        self,
        repository: str,
        token: str,
        *,
        api_url: str = API_URL,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        owner, sep, name = repository.partition("/")
        if not owner or not sep or not name or "/" in name:
            raise ValueError(f"repository must be 'owner/name', got {repository!r}")
        if not token:
            raise ValueError("an API token is required")
        self._prefix = f"/repos/{owner}/{name}"
        self._client = httpx.Client(
            base_url=api_url,
            timeout=httpx.Timeout(30.0),
            headers={
                "Authorization": f"Bearer {token}",
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": API_VERSION,
                "User-Agent": "talentforge-freshness-watch",
            },
            transport=transport,
        )

    def close(self) -> None:
        self._client.close()

    def __enter__(self) -> GitHubIssues:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    # ------------------------------------------------------------------ operations

    def open_issues_with(self, marker: str) -> list[Issue]:
        """Open issues (not pull requests) whose description contains ``marker``,
        oldest first."""
        found: list[Issue] = []
        for page in range(1, _MAX_PAGES + 1):
            params = {"state": "open", "per_page": str(_PAGE_SIZE), "page": str(page)}
            items = self._request("GET", "/issues", params=params)
            if not isinstance(items, list):
                raise GitHubIssuesError("GET issues did not return a list")
            for item in items:
                if not isinstance(item, dict):
                    raise GitHubIssuesError("GET issues returned a non-object entry")
                if "pull_request" in item:
                    continue
                issue = _issue(item)
                if marker in issue.body:
                    found.append(issue)
            if len(items) < _PAGE_SIZE:
                return sorted(found, key=lambda i: i.number)
        raise GitHubIssuesError(f"more than {_MAX_PAGES * _PAGE_SIZE} open issues: refusing")

    def create(self, title: str, body: str) -> Issue:
        return _issue(self._request("POST", "/issues", json={"title": title, "body": body}))

    def edit(self, number: int, title: str, body: str) -> None:
        self._request("PATCH", f"/issues/{number}", json={"title": title, "body": body})

    def comment(self, number: int, body: str) -> None:
        self._request("POST", f"/issues/{number}/comments", json={"body": body})

    def close_issue(self, number: int) -> None:
        self._request(
            "PATCH", f"/issues/{number}", json={"state": "closed", "state_reason": "completed"}
        )

    # ------------------------------------------------------------------ transport

    def _request(
        self,
        method: str,
        path: str,
        *,
        params: dict[str, str] | None = None,
        json: dict[str, str] | None = None,
    ) -> Any:
        what = f"{method} {self._prefix}{path}"
        try:
            response = self._client.request(method, self._prefix + path, params=params, json=json)
        except httpx.HTTPError as exc:
            raise GitHubIssuesError(f"{what} failed: {exc}") from exc
        if not response.is_success:
            status = response.status_code
            hint = _HINTS.get(status, "unexpected GitHub API error")
            raise GitHubIssuesError(
                f"{what} answered HTTP {status}: {hint}; GitHub said: {_message(response)}",
                status_code=status,
            )
        try:
            return response.json()
        except ValueError as exc:
            raise GitHubIssuesError(f"{what} returned non-JSON content") from exc


def _message(response: httpx.Response) -> str:
    try:
        payload = response.json()
    except ValueError:
        return response.text[:200]
    if isinstance(payload, dict) and isinstance(payload.get("message"), str):
        return str(payload["message"])
    return response.text[:200]


def _issue(item: object) -> Issue:
    if not isinstance(item, dict):
        raise GitHubIssuesError("an issue is not a JSON object")
    number, title, body, url = (item.get(k) for k in ("number", "title", "body", "html_url"))
    if body is None:
        body = ""  # GitHub sends null for an empty description
    if not (
        isinstance(number, int)
        and not isinstance(number, bool)
        and isinstance(title, str)
        and isinstance(body, str)
        and isinstance(url, str)
    ):
        raise GitHubIssuesError(f"unexpected issue shape: {sorted(item)}")
    return Issue(number=number, title=title, body=body, html_url=url)
