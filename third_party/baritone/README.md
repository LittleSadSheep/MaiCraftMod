# Embedded Baritone sources

MaiCraft vendors selected sources from Baritone commit `5f259b7f` (the upstream
`1.21.1` branch, Baritone 1.11.2) as its low-level path-planning and movement
backend. The imported files retain their original copyright and LGPL notices.

Baritone is licensed under the GNU Lesser General Public License version 3 or
later. MaiCraft as a combined work is distributed under GPL-3.0-only. The
upstream license text is preserved in this directory.

MaiCraft owns semantic intent, task scheduling, first-person control authority,
failure receipts, and cancellation. The embedded code does not register its chat
command surface. The optional native elytra pathfinder is not included in the
initial backend slice.
