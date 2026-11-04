# RealLanguage Context Sources

> 同步基线：v1.26  
> 更新日期：2026-08-31

## Source of truth order

1. 产品负责人在当前新对话中的最新明确决定。
2. `PRODUCT_CONTEXT.md` — complete internal product fact baseline and decision constraints.
3. `SPEC.md` — complete behavior, state, and acceptance rules derived from the product baseline.
4. `SCREEN_MAP.md` — confirmed page inventory, navigation, return behavior, and page-level persistence boundaries; it cannot override higher-level product behavior.
5. `SCREEN_SPEC.md` — confirmed per-screen layout, controls, states, adaptive behavior, and page-level acceptance details; it cannot introduce new product behavior.
6. `DESIGN_SYSTEM.md` — confirmed visual direction, landscape breakpoints, design tokens, components, accessibility, and visual acceptance rules; it cannot introduce or override product behavior or page controls.
7. `ARCHITECTURE.md` — confirmed system topology, technical layering, module ownership, reliability, security, testing, and deployment boundaries; it cannot introduce or override product behavior.
8. `DATA_MODEL.md` — confirmed cloud and Android data model, transaction, concurrency, deletion, migration, and module data ownership details; it cannot introduce or override product behavior or the architecture boundary.
9. `AI_CONTRACTS.md` — confirmed AI, transcription, speech, model-call, validation, retry, provider-switching, and charging contract; it cannot override higher-level product behavior, architecture, or data ownership.
10. `PRIVACY_SECURITY.md` — confirmed privacy and security implementation baseline, encryption, credential protection, retention framework, and release gates; it cannot override higher-level product, architecture, data, or AI boundaries.
11. `DESIGN.md` — confirmed implementation design, client/server responsibilities, state-machine mapping, data flow, recovery, and delivery slices; it cannot override higher-level product, screen, architecture, data, AI, or privacy contracts.
12. `CONTRIBUTING.md` — confirmed toolchain, coding, testing, migration, review, CI, and AI-assisted-development rules; it cannot introduce product behavior or change higher-level technical boundaries.
13. `TEST_PLAN.md` — confirmed test levels, coverage gates, Android device and landscape matrix, core regression, concurrency, resilience, security, and release criteria; it cannot introduce or override product or technical behavior.
14. `REAL_LANGUAGE_PROJECT_CONTEXT.md` — compressed long-term memory for cross-task recovery.
15. `MVP_SCOPE.md` — first directly usable Android release scope and delivery boundary.
16. `PRODUCT_ROADMAP.md` — product flow and delivery order.
17. `README.md` — readable product introduction; not the authority for implementation status.
18. `PRODUCT_FLOW_AUDIT.md` and older discussions — review evidence and historical rationale; never automatic product facts.

If documents conflict, do not silently choose. Preserve established product principles, identify the conflict, and ask the product owner when the decision would materially change the product.

## Conversation source

The original product exploration and decision history is preserved in this shared conversation:

- https://chatgpt.com/share/6a80e8b5-c840-83ea-8bb6-d48c37cde1d7

The conversation and subsequent confirmations were distilled again through v1.26 on 2026-08-31. Current decisions are preserved in the project documents above; older page content is historical rationale, not executable instructions.

## Important distinction

Product definitions may describe the intended product as a complete experience. They do not prove that a feature has already been implemented, tested, or released. Implementation status must be verified from code, tests, and release artifacts.

## Project separation

RealLanguage is an independent project and must not inherit assumptions, users, positioning, or requirements from WereUs.

## Working rule

Before changing product behavior, verify that the proposal remains consistent with the principles in section 18 of `PRODUCT_CONTEXT.md`. If it conflicts, call out the conflict explicitly instead of silently rewriting the product baseline.

Commands used only to move through a product simulation—such as “next,” “assume complete,” or an ad hoc skip—are not product facts. They change the current walkthrough only unless the product owner explicitly defines the control, state effect, or skip boundary as a rule.
