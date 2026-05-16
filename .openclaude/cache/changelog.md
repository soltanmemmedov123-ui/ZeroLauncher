## 0.11.0
- __section__:Features
- add sponsored tips with frequency-gated display
- groq: dynamic model discovery with mapModel filtering and hybrid catalog
- implement high-performance SQLite storage layer with JSON audit log (Phase 2 Masterpiece)
- nvidia-nim: add latest chat models, remove duplicate Mixtral 8x22B entry. Verified against integrate.api.nvidia.com/v1/models on 2026-05-13. Tracks #1099.
- provider: add Gitlawb Opengateway as default provider with MiMo
- provider: add Venice official provider
- provider: add Xiaomi MiMo integration
- __section__:Bug Fixes
- agent: prevent mid-flight peeking and taking over of forks
- bashPermissions: block command substitution in array subscript position
- bashSecurity: tighten fc -e detection to avoid long-flag false positives
- codex: normalize empty MCP object schemas
- errors: surface re-auth hint on OAuth token expiry 401s
- hide missing-module slash command stubs
- integrations: cap gpt-5.5 context window at Codex effective limit
- replace raw abort signal timeouts
- surface actionable error when fetch fails in _doOpenAIRequest
- update vulnerable dependencies

## 0.10.0
- __section__:Features
- Add startup logo palette picker
- cli: honor --model alone without requiring --provider
- incremental and cached token counting
- knowledge: introduce local Orama persistence (feature-flagged)
- make Orama the default search engine with JSON-backed
- websearch: add first-class Brave adapter; fix Google + Brave presets; restore Exa snippets
- __section__:Bug Fixes
- agent: ensure main agent waits for subagent completion
- agents: coerce non-string whenToUse to prevent crash on save
- bashSecurity: reject nested heredoc ranges in stripSafeHeredocSubstitutions
- effort: persist xhigh and send reasoning_effort on chat_completions
- openai-shim: redact ?auth=, ?passwd=, ?pwd= in diagnostic URLs (#1070) (20bc6ae)
- openai-shim: strip store for local providers (vLLM, custom)
- openai-shim: strip store when baseUrl points at Cerebras
- replace unsupported Unicode glyphs with widely available alternatives
- resolve two bugs making interactive mode unusable with plugin ecosystems
- validate plugin component paths
- __section__:Performance Improvements
- local: add OPENCLAUDE_LOCAL_FAST_PATH to skip cloud-only transforms (#1068) (4fad5d2)

## 0.9.2
- __section__:Bug Fixes
- cli: replace createRequire with static import for teammate.js

## 0.9.1
- __section__:Bug Fixes
- theme: remove stale memo wrappers from theme context hooks

## 0.9.0
- __section__:Features
- context partitioning and relevance-based pruning
- rework release notes around GitHub releases
- SDK Runtime — Query Engine, Sessions, and Build Pipeline
- support self-hosted Firecrawl via FIRECRAWL_API_URL
- __section__:Bug Fixes
- groq: strip unsupported store field
- mcp: allow third-party providers to approve project-scope .mcp.json servers
- shims: strip x-anthropic-billing-header block before forwarding system prompt
- startup: make CLAUDE logo D distinct
- tests: resolve flakiness due to module leak and env state leakage
- web-search: surface diagnostic when adapter returns 0 hits and no native fallback

## 0.8.0
- __section__:Features
- add Opus 4.7 as default model and fix alias/thinking bugs
- add streaming token counter
- api: deterministic request-body serialization via stableStringify
- cli: improve SSH interactivity detection via SSH_TTY and SSH_CONNECTION
- context preloading and hybrid context strategy
- lsp: add first-class code intelligence setup
- SDK Core — Permission System, Async Context, and Engine Extensions
- SDK Foundation — Type Declarations, Errors, and Utilities
- __section__:Bug Fixes
- avoid legacy Windows PasswordVault reads by default
- errors: show actual host in 404 message instead of Ollama hint
- input: strip leading ! when entering bash mode (#947) (5943c5c)
- oauth: skip refresh for third-party providers
- openai-shim: don't label transport failures as HTTP 503
- openai-shim: strip store when baseUrl points at Gemini (#959) (0f0fd26)
- plugins: sanitize env before spawning git so /plugin marketplace add works
- provider: apply Codex OAuth session switch correctly
- ripgrep: use @vscode/ripgrep package as the builtin source
- typecheck: make bun run typecheck actionable on main
- worktree: surface git stderr in rev-parse failure message

## 0.7.0
- __section__:Features
- add model-specific tokenizers and compression ratio detection
- add OPENCLAUDE_DISABLE_TOOL_REMINDERS env var to suppress hidden tool-output reminders (#837) (28de94d)
- add streaming optimizer and structured request logging
- add xAI as official provider
- api: expose cache metrics in REPL + normalize across providers
- implement Hook Chains runtime integration for self-healing agent mesh MVP
- memory: implement persistent project-level Knowledge Graph and RAG
- minimax: add /usage support and fix MiniMax quota parsing
- model: add GPT-5.5 support for Codex provider
- tools: resilient web search and fetch across all providers
- zai: add Z.AI GLM Coding Plan provider preset
- __section__:Bug Fixes
- agent: provider-aware fallback for haiku/sonnet aliases
- bugs
- make OpenAI fallback context window configurable + support external model lookup
- mcp: disable MCP_SKILLS feature flag — source not mirrored
- normalize /provider multi-model selection and semicolon parsing
- openai-shim: echo reasoning_content on assistant tool-call messages for Moonshot
- query: restore system prompt structure and add missing config import
- shell: recover when CWD path was replaced by a non-directory
- startup: show --model flag override on startup screen
- startup: url authoritative over model name in banner provider detect (#864) (e346b8d)
- surface actionable error when DuckDuckGo web search is rate-limited
- test: add missing teammate exports to hookChains integration mock (#840) (23e8cfb)
- update: show real package version and give actionable guidance

## 0.6.0
- __section__:Features
- add model caching and benchmarking utilities
- add thinking token extraction
- api: compress old tool_result content for small-context providers
- api: improve local provider reliability with readiness and self-healing
- api: smart model routing primitive (cheap-for-simple, strong-for-hard)
- enable 15 additional feature flags in open build
- native Anthropic API mode for Claude models on GitHub Copilot
- provider: expose Atomic Chat in /provider picker with autodetect
- provider: zero-config autodetection primitive
- __section__:Bug Fixes
- api: ensure strict role sequence and filter empty assistant messages after interruption (#745 regression)
- Collapse all-text arrays to string for DeepSeek compatibility
- model: codex/nvidia-nim/minimax now read OPENAI_MODEL env
- provider: saved profile ignored when stale CLAUDE_CODE_USE_* in shell
- rename .claude.json to .openclaude.json with legacy fallback
- replace discontinued gemini-2.5-pro-preview-03-25 with stable gemini-2.5-pro (#802) (64582c1)
- security: harden project settings trust boundary + MCP sanitization
- test: autoCompact floor assertion is flag-sensitive
- ui: prevent provider manager lag by deferring sync I/O

## 0.5.2
- __section__:Bug Fixes
- api: replace phrase-based reasoning sanitizer with tag-based filter

## 0.5.1
- __section__:Bug Fixes
- enforce Bash path constraints after sandbox allow
- enforce MCP OAuth callback state before errors
- require trusted approval for sandbox override