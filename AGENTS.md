# Working conventions

- Always start with a short plan before editing.
- Prefer minimal diffs.
- Ask before large refactors or new dependencies.
- After changes, run relevant tests if available.
- Explain what changed in plain language.
- Be concise and act like a collaborative pair programmer.

## Feature worktrees

- Implement each GitHub ticket in a separate Git worktree.
- Name the branch `feature-<github-ticket-number>` and place its checkout at
  `.worktrees/feature-<github-ticket-number>` under the main repository checkout.
- Check `git worktree list` first and reuse an existing checkout for the ticket.
- If the branch already exists, use it; otherwise create it from the agreed base
  (the current committed HEAD unless the task specifies another base).
- Keep unrelated uncommitted changes in their original checkout. Run edits,
  builds, and tests from the ticket's worktree.
- Do not invent ticket numbers or remove worktrees without a user request.

## Code discovery

Prefer codebase-memory-mcp graph tools over text or file searches for code
discovery: `search_graph`, `trace_path`, `get_code_snippet`, then `query_graph`.
Use `get_architecture` for a high-level summary. Run `index_repository` first if
the project is not indexed. Fall back to text searches for string literals,
configuration, non-code files, or when graph results are insufficient.
