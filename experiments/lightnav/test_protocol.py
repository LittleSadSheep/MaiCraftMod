# SPDX-License-Identifier: GPL-3.0-only
"""Transport contract regressions; no server, model, or game process required."""

import copy
import json
import unittest

from protocol import ProtocolError, exchange


class FakeSocket:
    def __init__(self, *replies):
        self.replies = iter(replies)
        self.sent = []

    def send(self, message):
        self.sent.append(json.loads(message))

    def recv(self, *, timeout):
        return next(self.replies)


class ProtocolTests(unittest.TestCase):
    def request(self, instruction="walk to the door"):
        return {"seq": 7, "image": "data:image/png;base64,unchanged==",
                "instruction": instruction}

    def reply(self):
        return {"action": "next", "data": {
            "rc": 0, "seq": 7,
            "actions": {"actions": [[1.0, 0, 0.1], [2.5, -1, 0.3]]},
            "stop": False, "visible": None}}

    def run_exchange(self, reply, request=None):
        socket = FakeSocket(json.dumps(reply))
        return exchange(socket, "next", self.request() if request is None else request, 2)

    def test_login_then_reset_preserve_request_and_response_envelopes(self):
        replies = [{"action": name, "data": {"rc": 0}}
                   for name in ("login", "reset")]
        socket = FakeSocket(*(json.dumps(reply) for reply in replies))
        login = {"clientId": "maicraft-lightnav-replay"}
        self.assertEqual(exchange(socket, "login", login, 2), replies[0])
        self.assertEqual(exchange(socket, "reset", {}, 2), replies[1])
        self.assertEqual(socket.sent, [{"action": "login", "data": login},
                                       {"action": "reset", "data": {}}])

    def test_next_preserves_image_instruction_and_cumulative_waypoints(self):
        request = self.request("走到门口，不要上楼")
        original = copy.deepcopy(request)
        reply = self.reply()
        socket = FakeSocket(json.dumps(reply))
        self.assertEqual(exchange(socket, "next", request, 2), reply)
        self.assertEqual(socket.sent, [{"action": "next", "data": original}])
        self.assertEqual(request, original)

    def test_server_errors_are_rejected(self):
        for code in (400, 500):
            with self.subTest(code=code), self.assertRaises(ProtocolError):
                reply = self.reply()
                reply["data"]["rc"] = code
                self.run_exchange(reply)

    def test_return_code_requires_integer_zero(self):
        for code in (None, False, True, "0", 0.0):
            with self.subTest(code=code), self.assertRaises(ProtocolError):
                reply = self.reply()
                reply["data"]["rc"] = code
                self.run_exchange(reply)

    def test_mismatched_action_or_sequence_is_rejected(self):
        reply = self.reply()
        reply["action"] = "reset"
        with self.assertRaises(ProtocolError):
            self.run_exchange(reply)
        for seq in (None, 6, 8, "7", 7.0, True):
            with self.subTest(seq=seq), self.assertRaises(ProtocolError):
                reply = self.reply()
                reply["data"]["seq"] = seq
                self.run_exchange(reply)

    def test_malformed_json_and_envelopes_are_rejected(self):
        for payload in ("{", "null", "[]", '"text"', "{}",
                        '{"action":"next","data":null}',
                        '{"action":"next","data":[]}',
                        '{"action":"next","data":{"seq":7}}'):
            with self.subTest(payload=payload), self.assertRaises(ProtocolError):
                exchange(FakeSocket(payload), "next", self.request(), 2)

    def test_waypoints_must_be_nonempty_finite_triples(self):
        invalid = [None, [], {}, [[1, 2]], [[1, 2, 3, 4]], ["123"],
                   [[True, 0, 0]], [["1", 0, 0]], [[None, 0, 0]]]
        invalid += [[[value, 0, 0]] for value in
                    (float("nan"), float("inf"), -float("inf"))]
        for waypoints in invalid:
            with self.subTest(waypoints=waypoints), self.assertRaises(ProtocolError):
                reply = self.reply()
                reply["data"]["actions"]["actions"] = waypoints
                self.run_exchange(reply)

    def test_missing_prediction_or_waypoints_is_rejected(self):
        for missing in ("prediction", "waypoints"):
            reply = self.reply()
            if missing == "prediction":
                del reply["data"]["actions"]
            else:
                del reply["data"]["actions"]["actions"]
            with self.subTest(missing=missing), self.assertRaises(ProtocolError):
                self.run_exchange(reply)

    def test_stop_is_required_and_boolean(self):
        for stop in ("missing", None, 0, 1, "false"):
            reply = self.reply()
            if stop == "missing":
                del reply["data"]["stop"]
            else:
                reply["data"]["stop"] = stop
            with self.subTest(stop=stop), self.assertRaises(ProtocolError):
                self.run_exchange(reply)
        reply = self.reply()
        reply["data"]["stop"] = True
        self.assertEqual(self.run_exchange(reply), reply)

    def test_visibility_is_optional_nullable_or_boolean(self):
        for visible in ("missing", None, False, True):
            reply = self.reply()
            if visible == "missing":
                del reply["data"]["visible"]
            else:
                reply["data"]["visible"] = visible
            with self.subTest(visible=visible):
                self.assertEqual(self.run_exchange(reply), reply)
        for visible in (0, 1, "true", []):
            reply = self.reply()
            reply["data"]["visible"] = visible
            with self.subTest(visible=visible), self.assertRaises(ProtocolError):
                self.run_exchange(reply)

    def test_buffer_only_requests_accept_ack_without_prediction(self):
        reply = {"action": "next", "data": {"rc": 0, "seq": 7}}
        for instruction in ("", None):
            with self.subTest(instruction=instruction):
                self.assertEqual(self.run_exchange(reply, self.request(instruction)), reply)


if __name__ == "__main__":
    unittest.main()
