# T219: Ctrl-C/SIGTERM branch audit

Goal: the T194/T205 branches all implement the same Ctrl-C/SIGTERM recording flush. Only one
implementation may land. Audited against `task-219` (899417b, = `agentherd`).

## Verdict

The flush is already landed **exactly once**, as T205 (`bd48cd0`, merged in `77141f7`).
`src/main.rs` has one `static QUIT`, one `ctrlc::set_handler` call, and one `quit_requested()`;
`Cargo.toml`/`Cargo.lock` have one `ctrlc` dependency. `cargo test`: 11 passed, 0 failed.

No further code merge is needed. Retire the duplicate branches below; do not merge any of them.

## Branch-by-branch

| Branch | Commit | Base | What merging would do to the current tree | Action |
|---|---|---|---|---|
| `task-205` | `bd48cd0` | `ca832b7` (current code) | nothing new: already merged as `77141f7` | keep (delete branch after merge) |
| `task-194` | `fe60da9` | `e83f2ff` (pre-T195) | replaces the landed `QUIT`/`quit_requested` code with `INTERRUPTED` and **reverts T195** (threaded `diagnose()`, so diagnostics would stall the stream again) | delete, do not merge |
| `task-194-conflict-1` | `c805fbd` | `81a1cf9` (pre-T182/T193/T195) | **reverts T182** (`first_idr`, keyframe-start recordings), **T193** (`RecordingUtil` + tests) and **T195**; swaps in `STOP`/inline diagnose | delete, do not merge |
| `task-211-conflict-1` | `15ac6b0` | `ca832b7` (pre-T205) | **removes the ctrlc flush entirely** (`-2` Cargo.toml, `-15` main.rs, `-72` Cargo.lock) and reverts the T204 notes refresh | delete, do not merge |
| `task-211` | `c7d3705` | `899417b` (current) | docs only: adds the same T194 hardening bullet T215 adds | drop if T215 is the accepted notes (else it is the clean alternative) |
| `task-215` | `4a76e88` | `77141f7` (pre-T204 notes) | docs only: adds the T194/T205 bullet, but its tree predates T204's notes refresh | accepted notes; merge/rebase only this one, not `task-211` |

## Notes on T215

`4a76e88` (T215) is not an ancestor of this worktree's `agentherd` (899417b); it was built on
`77141f7`. Its commit itself only adds the T194/T205 bullet and the human-verification item.
When merging it, keep T204's T182/T189/T193/T195 bullets (a conflict in the hardening section is
expected; resolve by keeping both). Do **not** also merge `task-211` (`c7d3705`) or
`task-211-conflict-1` (`15ac6b0`) — that would duplicate the hardening bullet and, for
`15ac6b0`, drop the landed code.

## Retirement list

Delete these branches; their commits are either merged already or superseded:

- `task-194` (`fe60da9`)
- `task-194-conflict-1` (`c805fbd`)
- `task-211-conflict-1` (`15ac6b0`)
- `task-205` (`bd48cd0`) after `77141f7` is on `main`
- `task-211` (`c7d3705`) once T215's notes are accepted

Only `task-215` (`4a76e88`) remains a candidate to merge, and only for docs.
