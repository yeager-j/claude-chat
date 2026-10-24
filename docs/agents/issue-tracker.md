# Issue tracker: Linear

Issues and specs for this repo live in Linear, in the **ClaudeChat** team (key `MAI`, so issues are `MAI-123`). Use the Linear MCP tools (`list_issues`, `get_issue`, `save_issue`, `save_comment`, `list_issue_statuses`, …). GitHub Issues are not used; GitHub holds only the code and PRs.

## Conventions

- **Create an issue**: `save_issue` with `team: "ClaudeChat"`, a title and a Markdown description. New issues start in **Backlog** unless the work is ready, then **Todo**.
- **Read an issue**: `get_issue` with its identifier (`MAI-123`); `list_comments` for the discussion.
- **List issues**: `list_issues` with `team: "ClaudeChat"` and filters for state, label, project or assignee.
- **Sub-issues**: set the parent on the child (`save_issue` with the parent's id). Without that, put `Part of MAI-<n>` at the top of the child's description.
- **Comment**: `save_comment` on the issue.
- **Labels**: apply the team's existing labels; list them with `list_issue_labels` before creating new ones.
- **Close**: move the issue to **Done** (or **Canceled** / **Duplicate**) and comment why.

Statuses in this team: Backlog, Todo, In Progress, In Review, Done, Canceled, Duplicate.

Reference issues in commits and PRs by identifier (`MAI-123`); Linear's GitHub integration links them.

## When a skill says "publish to the issue tracker"

Create a Linear issue in the ClaudeChat team.

## When a skill says "fetch the relevant ticket"

Read it as in **Read an issue** above.

## Wayfinding operations

Used by `/wayfinder`. The **map** is a single issue with **child** issues as tickets.

- **Map**: one ClaudeChat issue labelled `wayfinder:map`, holding the Notes / Decisions-so-far / Fog body.
- **Child ticket**: a sub-issue of the map, labelled `wayfinder:<type>` (`research`/`prototype`/`grilling`/`task`). Once claimed, it is assigned to the driving dev.
- **Blocking**: Linear's native "blocked by" relations between issues. A ticket is unblocked when every blocker is Done, Canceled or Duplicate.
- **Frontier query**: list the map's open sub-issues, drop any with an open blocker or an assignee; first in map order wins.
- **Claim**: assign the issue to yourself and move it to **In Progress**, the session's first write.
- **Resolve**: comment the answer, move the issue to **Done**, then append a context pointer (gist + link) to the map's Decisions-so-far.
