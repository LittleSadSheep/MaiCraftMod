# SPDX-License-Identifier: GPL-3.0-only
"""Minimal checked client for LightNav's JSON-over-WebSocket protocol."""

import json
import math


class ProtocolError(ValueError):
    """The server rejected a request or returned an unusable prediction."""


def _finite_number(value):
    return type(value) in (int, float) and math.isfinite(value)


def exchange(ws, action: str, data: dict, timeout: float) -> dict:
    """Exchange exactly one request; never reconnect and silently lose history."""
    ws.send(json.dumps({"action": action, "data": data}, allow_nan=False))
    raw = ws.recv(timeout=timeout)
    try:
        reply = json.loads(raw)
    except (ValueError, TypeError) as exc:
        raise ProtocolError("Server response is not JSON") from exc
    if not isinstance(reply, dict) or reply.get("action") != action:
        raise ProtocolError(f"Expected a response for {action}")
    result = reply.get("data")
    if not isinstance(result, dict) or type(result.get("rc")) is not int:
        raise ProtocolError("Server response has no integer data.rc")
    if result["rc"] != 0:
        raise ProtocolError(f"LightNav rc={result['rc']}: {result.get('msg', '')}")
    if action == "next":
        if type(result.get("seq")) is not int or result["seq"] != data["seq"]:
            raise ProtocolError("Response sequence does not match the input frame")
        if data.get("instruction"):
            _validate_prediction(result)
    return reply


def _validate_prediction(result):
    actions = result.get("actions")
    rows = actions.get("actions") if isinstance(actions, dict) else None
    if not isinstance(rows, list) or not rows:
        raise ProtocolError("Prediction has no waypoint chunk")
    for row in rows:
        if not isinstance(row, list) or len(row) != 3 or not all(
            _finite_number(value) for value in row
        ):
            raise ProtocolError("Waypoints must contain three finite numbers per row")
    if type(result.get("stop")) is not bool:
        raise ProtocolError("Prediction has no boolean stop flag")
    if result.get("visible") is not None and type(result["visible"]) is not bool:
        raise ProtocolError("Prediction visibility must be boolean or null")
