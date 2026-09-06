# Modified Baritone sources

MaiCraft vendors selected sources from Baritone commit `5f259b7f` (the upstream
`1.21.1` branch, Baritone 1.11.2) as its low-level path-planning and movement
backend. The imported files have been modified for MaiCraft since 2026-07-30.

Upstream Baritone is available under LGPL-3.0-or-later. For the modified copy in
this repository, MaiCraft removes the LGPLv3 additional permissions and any
non-permissive additional terms under section 7 of GNU GPLv3. This copy is
distributed under GPL-3.0-only; the repository root `LICENSE` applies. The
upstream authors and contributors retain their copyright.

MaiCraft owns semantic intent, task scheduling, first-person control authority,
failure receipts, and cancellation. The embedded code does not register its chat
command surface. The optional native elytra pathfinder is not included in the
initial backend slice.
