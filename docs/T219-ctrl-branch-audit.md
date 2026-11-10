# T219: Ctrl-C/SIGTERM branch audit

Goal: the T194/T205 branches all implement the same Ctrl-C/SIGTERM recording flush. Only one
implementation may land. Audited against `agentherd` = **`0f47b84`** (T218 tip), not this
worktree's `899417b`. `agentherd` already contains T209 (`94bac69`/`7b560f4`), T210/T213, T211's
notes (`f01ceb1`) and T218 (`af2c4e5`), so it is the tree that matters for retirement.

## Verdict

The flush is already landed **exactly once**, as T205 (`bd48cd0`, merged in `77141f7`, both in
`agentherd`'s history). `src/main.rs` has one `static QUIT` (:18), exactly one
`ctrlc::set_handler` call (:163, the only one in the tree) and one `quit_requested()` (:258);
`Cargo.toml`/`Cargo.lock` have one `ctrlc` dependency. No further code merge is needed.

Test count: `agentherd`'s own TEAM_NOTES record `cargo test` = **12 passed** (T209, macOS); the
12th is T209's `finish_recording_flushes_buffered_tail` (`src/main.rs:816`). `cargo test` in
this T219 worktree (based on `899417b`, before T209) gives **11 passed, 0 failed** — the T209
test is simply not in this branch yet. The 12-vs-11 difference is ancestry, not a defect.

**Do not merge** any of: `task-194` (`fe60da9`), `task-194-conflict-1` (`c805fbd`),
`task-211-conflict-1` (`15ac6b0`), `task-211-conflict-2` (`c7d3705`),
`task-215-conflict-1` (`4a76e88`).

## Branch map (refs verified)

| Branch | Commit | Parent / merge-base | Own delta | What a 3-way merge into `agentherd` would do | Action |
|---|---|---|---|---|---|
| *(no branch)* | `bd48cd0` / `77141f7` | `ca832b7` | landed T205 | already in `agentherd`; no branch points at either commit | keep; nothing to delete |
| `task-194` | `fe60da9` | `e83f2ff` | code: `ctrlc` dep, `INTERRUPTED` static, handler at the top of `main()`, loop checks | adds a second atomic (`INTERRUPTED`) beside `QUIT` and a second `ctrlc::set_handler` call; the crate allows one handler, so the second returns `MultipleHandlers` (T205 uses `?`, so startup aborts if that call runs second). Its hunks do not overlap T182/T193/T195, so it does **not** revert them | delete, do not merge |
| `task-194-conflict-1` | `c805fbd` | `81a1cf9` | code: `ctrlc` dep, `STOP` static, handler after window creation, loop checks, terminal status print on exit | same duplicate `STOP` + `QUIT` statics and a second `set_handler` (this variant logs `MultipleHandlers` instead of failing) plus an extra terminal print; does **not** revert T182/T193/T195 | delete, do not merge |
| `task-211-conflict-1` | `15ac6b0` | `ca832b7` | docs only: T194 bullet + one human-verification line | duplicates the T194/T205/T209 bullet and verification line `agentherd` already has via `f01ceb1` (expect a notes conflict); touches no code, so it cannot drop the flush in a merge | delete, do not merge |
| `task-211-conflict-2` | `c7d3705` | `899417b` | docs only (5+/3-): same T194 bullet + verification line | same duplicate/conflict; content superseded by `f01ceb1`; touches no code | delete, do not merge |
| `task-215-conflict-1` | `4a76e88` | `77141f7` | docs only (3+/1-): T194/T205 bullet + verification line, built before T204/T209/T211/T218 | duplicate/conflicting bullet in the hardening and human-verification sections; touches no code | delete, do not merge |
| `task-215` | `43f0819` | `f01ceb1` (already on `agentherd`) | docs only (2 lines reworded): rewrites the existing T194/T205/T209 human-verification bullet and section wording; adds no new bullet | no duplicate and no code change; `agentherd` already has the bullet and verification line from `f01ceb1`, only the wording differs | optional wording-only merge; no merge needed |

There is **no `task-205` branch and no `task-211` branch**. `bd48cd0` is reachable only through
`77141f7`; `c7d3705` is `task-211-conflict-2`; `4a76e88` is `task-215-conflict-1`.

`git diff agentherd <branch>` is not a merge preview. For the doc-only branches it shows
`main.rs`/`RecordingUtilTest.java` removals only because those branches were cut before
T209/T218; a merge does not apply an ancestor's missing commits as deletions.

## Notes on T215

`task-215` (`43f0819`, parent `f01ceb1`) is **not** "the candidate that adds the bullet". The
T194 bullet and the human-verification line are already on `agentherd` via T211's merge
`f01ceb1`. `git diff agentherd 43f0819` is a two-paragraph reword: the verification bullet gains
"nothing is printed to the terminal" and the T194/T205/T209 section notes that `main.rs:163` is
the only `ctrlc::set_handler` call. That content largely matches what is already there, so no
merge is required. Do not merge `task-211-conflict-1` (`15ac6b0`) or `task-211-conflict-2`
(`c7d3705`) either: both would duplicate the bullet that `f01ceb1` already landed.

`task-215-conflict-1` (`4a76e88`) is the older variant built on `77141f7`; its diff against
`agentherd` drops T204's notes refresh, T209's test, T211's notes and T218's Android test only
because it never had them. Do not merge it.

## Retirement list

Refs that exist and can be deleted (the orchestrator does the deletion; agents in this task
cannot run `git branch -D`):

- `task-194` (`fe60da9`)
- `task-194-conflict-1` (`c805fbd`)
- `task-211-conflict-1` (`15ac6b0`)
- `task-211-conflict-2` (`c7d3705`)
- `task-215-conflict-1` (`4a76e88`)

`task-215` (`43f0819`) may be kept or deleted at the orchestrator's discretion; it is not needed
for the flush or the notes. There is no ref for the landed T205 commits (`bd48cd0`, `77141f7`),
so nothing to delete there.
