# Skills for your AI

Repository-maintained Agent Skills for working with Actron. The instructions are in English
so they can be reused across teams; agents can answer in the user's language.

| Skill | Use it for |
|---|---|
| [actron-model](actron-model/SKILL.md) | Choose modules, describe typed machines, wire execution and UI |
| [actron-test](actron-test/SKILL.md) | Virtual-time scenarios, scripted commands, coverage and regression tests |
| [actron-inspect](actron-inspect/SKILL.md) | Graph diagnostics, recordings, replay, checkpoints and causal traces |

Each directory contains a portable `SKILL.md` and optional Codex UI metadata. These are
source-controlled instructions, not runtime dependencies, plugins or a background agent.

To use without installation, ask your agent to read the desired `skills/<name>/SKILL.md`
in this checkout. For agents with skill discovery, install or link each skill directory
using that agent's supported skill location. Keep the optional `references/` directory
with its skill. Merely cloning this repository does not guarantee automatic discovery.
No global agent configuration or credentials are installed by this project.

The skills intentionally check the current checkout's API before suggesting code. Features
can differ between main, release tags and development branches. Update the relevant skill
alongside API changes, and keep executable examples in Kotlin tests rather than duplicating
large samples in these files.
