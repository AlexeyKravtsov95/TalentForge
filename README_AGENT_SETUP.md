# TalentForge agent setup

Copy this bundle into the root of the TalentForge repository.

```text
CLAUDE.md
AGENTS.md
.claude/
└── skills/
    ├── talentforge-engineering/SKILL.md
    ├── talentforge-contract-safety/SKILL.md
    ├── talentforge-ui/SKILL.md
    └── talentforge-pr-workflow/SKILL.md
```

## Claude Code / Opus

Claude Code uses the repository `CLAUDE.md` and project skills under `.claude/skills/`.

Explicit invocation:

```text
$talentforge-engineering
$talentforge-contract-safety
$talentforge-ui
$talentforge-pr-workflow
```

## ZCode / GLM

ZCode Agent uses the workspace `AGENTS.md` for always-on project instructions.

Keep the canonical skills under `.claude/skills/`. In ZCode:

1. Open `Settings -> Skills`.
2. Choose `Import`.
3. Select the detected Claude Code project skills.
4. Choose target `Project`.
5. Choose import mode `Symlink`.
6. Refresh and confirm all four skills are enabled.

This avoids maintaining a second divergent copy under `.zcode/skills/`.

## Recommended combinations

Data/allocation work:

```text
$talentforge-engineering
$talentforge-contract-safety
$talentforge-pr-workflow
```

Production UI work:

```text
$talentforge-engineering
$talentforge-ui
$talentforge-pr-workflow
```

For work spanning both data semantics and UI, invoke all relevant skills.
