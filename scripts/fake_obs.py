#!/usr/bin/env python3
import argparse
import base64
import hashlib
import json
import os
import random
import socket
import struct
import threading
import time

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
MAX_HEAD = 16 * 1024
MAX_FRAME = 4 * 1024 * 1024
SUBPROTOCOL = "obswebsocket.json"
CLOSE_AUTH_FAILED = 4009
CLOSE_NOT_IDENTIFIED = 4007
CLOSE_DECODE_ERROR = 4002

OUTPUT_STARTING = "OBS_WEBSOCKET_OUTPUT_STARTING"
OUTPUT_STARTED = "OBS_WEBSOCKET_OUTPUT_STARTED"
OUTPUT_STOPPING = "OBS_WEBSOCKET_OUTPUT_STOPPING"
OUTPUT_STOPPED = "OBS_WEBSOCKET_OUTPUT_STOPPED"

HOSTILE_SCENES = [
    "Plain scene",
    "‮evil rtl name",
    "line\r\nbreak\tname",
    "<b>markup</b> &amp; %s %n",
    "zalgo" + "́" * 200,
    "x" * 400,
    "​‍﻿",
    "emoji \U0001f3ac scene",
]
HOSTILE_INPUTS = ["Mic/Aux", "‮Desktop Audio", "a" * 300, "<i>Music</i>"]


def auth_response(password, salt, challenge):
    secret = base64.b64encode(hashlib.sha256((password + salt).encode("utf-8")).digest()).decode("ascii")
    return base64.b64encode(hashlib.sha256((secret + challenge).encode("utf-8")).digest()).decode("ascii")


class Closed(Exception):
    pass


class FakeObs:
    def __init__(self, args):
        self.args = args
        self.lock = threading.RLock()
        self.clients = []
        if args.hostile:
            self.scenes = list(HOSTILE_SCENES)
            self.audio = {name: False for name in HOSTILE_INPUTS}
        else:
            self.scenes = ["Starting Soon", "Gameplay", "Camera", "Be Right Back", "Ending"]
            self.audio = {"Mic/Aux": False, "Desktop Audio": False, "Music": True}
        self.video_inputs = ["Webcam", "Game Capture"]
        self.program = self.scenes[0]
        self.stream_started = None
        self.record_started = None
        self.frames = 0
        self.started_at = time.monotonic()

    def log(self, text):
        if not self.args.quiet:
            print(time.strftime("%H:%M:%S"), text, flush=True)

    def broadcast(self, event_type, data):
        message = {"op": 5, "d": {"eventType": event_type, "eventIntent": 0, "eventData": data}}
        with self.lock:
            targets = list(self.clients)
        for client in targets:
            client.send_json(message)

    def later(self, delay, action):
        timer = threading.Timer(delay, action)
        timer.daemon = True
        timer.start()

    def set_output(self, event_type, active, state):
        self.broadcast(event_type, {"outputActive": active, "outputState": state, "outputPath": None})

    def handle_request(self, request_type, data):
        with self.lock:
            return self.dispatch(request_type, data)

    def dispatch(self, request_type, data):
        if request_type == "GetSceneList":
            count = len(self.scenes)
            scenes = [
                {"sceneIndex": count - 1 - index, "sceneName": name, "sceneUuid": f"uuid-{index}"}
                for index, name in enumerate(self.scenes)
            ]
            scenes.reverse()
            return True, 100, None, {
                "currentProgramSceneName": self.program,
                "currentProgramSceneUuid": "uuid",
                "currentPreviewSceneName": None,
                "currentPreviewSceneUuid": None,
                "scenes": scenes,
            }
        if request_type == "SetCurrentProgramScene":
            name = data.get("sceneName")
            if name not in self.scenes:
                return False, 600, "No source was found by the name of the scene", None
            self.program = name
            self.log(f"program scene: {name!r}")
            self.later(0.02, lambda: self.broadcast("CurrentProgramSceneChanged", {"sceneName": name, "sceneUuid": "uuid"}))
            return True, 100, None, None
        if request_type == "GetInputList":
            names = list(self.audio) + self.video_inputs
            return True, 100, None, {
                "inputs": [
                    {"inputName": name, "inputUuid": "uuid", "inputKind": "kind", "unversionedInputKind": "kind"}
                    for name in names
                ]
            }
        if request_type == "GetInputMute":
            name = data.get("inputName")
            if name in self.audio:
                return True, 100, None, {"inputMuted": self.audio[name]}
            if name in self.video_inputs:
                return False, 604, "The specified input does not support audio", None
            return False, 600, "No source was found", None
        if request_type == "SetInputMute":
            name = data.get("inputName")
            muted = data.get("inputMuted")
            if name not in self.audio or not isinstance(muted, bool):
                return False, 600, "No source was found", None
            self.audio[name] = muted
            self.log(f"mute {name!r}: {muted}")
            self.later(0.02, lambda: self.broadcast("InputMuteStateChanged", {"inputName": name, "inputUuid": "uuid", "inputMuted": muted}))
            return True, 100, None, None
        if request_type == "GetStats":
            self.frames = int((time.monotonic() - self.started_at) * 60)
            return True, 100, None, {
                "cpuUsage": random.uniform(4.0, 18.0),
                "memoryUsage": 512.0,
                "availableDiskSpace": 100000.0,
                "activeFps": 60.0,
                "averageFrameRenderTime": 2.5,
                "renderSkippedFrames": 3,
                "renderTotalFrames": self.frames,
                "outputSkippedFrames": 1,
                "outputTotalFrames": self.frames,
                "webSocketSessionIncomingMessages": 0,
                "webSocketSessionOutgoingMessages": 0,
            }
        if request_type == "GetStreamStatus":
            active = self.stream_started is not None
            duration = int((time.monotonic() - self.stream_started) * 1000) if active else 0
            total = duration * 60 // 1000
            return True, 100, None, {
                "outputActive": active,
                "outputReconnecting": False,
                "outputTimecode": "00:00:00.000",
                "outputDuration": duration,
                "outputCongestion": 0.0,
                "outputBytes": duration * 500,
                "outputSkippedFrames": total // 200,
                "outputTotalFrames": total,
            }
        if request_type == "GetRecordStatus":
            active = self.record_started is not None
            duration = int((time.monotonic() - self.record_started) * 1000) if active else 0
            return True, 100, None, {
                "outputActive": active,
                "outputPaused": False,
                "outputTimecode": "00:00:00.000",
                "outputDuration": duration,
                "outputBytes": duration * 500,
            }
        if request_type == "StartStream":
            if self.stream_started is not None:
                return False, 500, "The stream output is already running", None
            self.stream_started = time.monotonic()
            self.log("stream start")
            self.set_output("StreamStateChanged", False, OUTPUT_STARTING)
            self.later(0.5, lambda: self.set_output("StreamStateChanged", True, OUTPUT_STARTED))
            return True, 100, None, None
        if request_type == "StopStream":
            if self.stream_started is None:
                return False, 501, "The stream output is not running", None
            self.stream_started = None
            self.log("stream stop")
            self.set_output("StreamStateChanged", True, OUTPUT_STOPPING)
            self.later(0.5, lambda: self.set_output("StreamStateChanged", False, OUTPUT_STOPPED))
            return True, 100, None, None
        if request_type == "StartRecord":
            if self.record_started is not None:
                return False, 500, "The record output is already running", None
            self.record_started = time.monotonic()
            self.log("record start")
            self.set_output("RecordStateChanged", True, OUTPUT_STARTED)
            return True, 100, None, None
        if request_type == "StopRecord":
            if self.record_started is None:
                return False, 501, "The record output is not running", None
            self.record_started = None
            self.log("record stop")
            self.set_output("RecordStateChanged", False, OUTPUT_STOPPED)
            return True, 100, None, {"outputPath": "fake.mkv"}
        return False, 204, "Unknown request type", None

    def rotate_scene(self):
        with self.lock:
            index = self.scenes.index(self.program)
            self.program = self.scenes[(index + 1) % len(self.scenes)]
            name = self.program
        self.broadcast("CurrentProgramSceneChanged", {"sceneName": name, "sceneUuid": "uuid"})

    def serve(self):
        family = socket.AF_INET6 if ":" in self.args.host else socket.AF_INET
        listener = socket.socket(family, socket.SOCK_STREAM)
        if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        else:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind((self.args.host, self.args.port))
        listener.listen(16)
        self.log(f"fake obs-websocket 5 on {self.args.host}:{self.args.port}, password {'on' if self.args.password else 'off'}")
        if self.args.rotate_scene > 0:
            threading.Thread(target=self.rotate_loop, daemon=True).start()
        try:
            while True:
                connection, peer = listener.accept()
                self.log(f"client {peer}")
                client = Client(self, connection)
                threading.Thread(target=client.run, daemon=True).start()
        except KeyboardInterrupt:
            self.log("stopping")
        finally:
            listener.close()

    def rotate_loop(self):
        while True:
            time.sleep(self.args.rotate_scene)
            self.rotate_scene()


class Client:
    def __init__(self, server, connection):
        self.server = server
        self.connection = connection
        self.send_lock = threading.Lock()
        self.identified = False
        self.salt = base64.b64encode(os.urandom(32)).decode("ascii")
        self.challenge = base64.b64encode(os.urandom(32)).decode("ascii")
        self.connected_at = time.monotonic()

    def run(self):
        try:
            self.connection.settimeout(60)
            self.connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.handshake()
            self.send_hello()
            while True:
                opcode, payload = self.read_message()
                if opcode == 8:
                    self.send_frame(8, payload[:125])
                    return
                if opcode == 9:
                    self.send_frame(10, payload)
                elif opcode == 1:
                    self.on_text(payload)
                if self.server.args.drop_after > 0 and time.monotonic() - self.connected_at > self.server.args.drop_after:
                    self.server.log("dropping the client on purpose")
                    return
        except (Closed, OSError, ValueError):
            pass
        finally:
            with self.server.lock:
                if self in self.server.clients:
                    self.server.clients.remove(self)
            try:
                self.connection.close()
            except OSError:
                pass
            self.server.log("client gone")

    def read_exact(self, count):
        data = bytearray()
        while len(data) < count:
            chunk = self.connection.recv(min(65536, count - len(data)))
            if not chunk:
                raise Closed()
            data += chunk
        return bytes(data)

    def handshake(self):
        head = bytearray()
        while not head.endswith(b"\r\n\r\n"):
            if len(head) >= MAX_HEAD:
                raise Closed()
            head += self.read_exact(1)
        lines = head.decode("latin-1").split("\r\n")
        headers = {}
        for line in lines[1:]:
            if ":" in line:
                name, value = line.split(":", 1)
                headers[name.strip().lower()] = value.strip()
        key = headers.get("sec-websocket-key")
        if not lines[0].startswith("GET ") or not key or headers.get("upgrade", "").lower() != "websocket":
            self.connection.sendall(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n")
            raise Closed()
        accept = base64.b64encode(hashlib.sha1((key + GUID).encode("ascii")).digest()).decode("ascii")
        response = (
            "HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Accept: {accept}\r\n"
            f"Sec-WebSocket-Protocol: {SUBPROTOCOL}\r\n"
            "\r\n"
        )
        self.connection.sendall(response.encode("ascii"))

    def read_frame(self):
        first, second = self.read_exact(2)
        if first & 0x70:
            raise Closed()
        if not second & 0x80:
            self.close(1002)
            raise Closed()
        length = second & 0x7F
        if length == 126:
            length = struct.unpack(">H", self.read_exact(2))[0]
        elif length == 127:
            length = struct.unpack(">Q", self.read_exact(8))[0]
        if length > MAX_FRAME:
            self.close(1009)
            raise Closed()
        mask = self.read_exact(4)
        data = bytearray(self.read_exact(length))
        for index in range(length):
            data[index] ^= mask[index % 4]
        return first & 0x80 != 0, first & 0x0F, bytes(data)

    def read_message(self):
        fin, opcode, payload = self.read_frame()
        if opcode >= 8 or fin:
            return opcode, payload
        buffer = bytearray(payload)
        while True:
            fin, next_opcode, part = self.read_frame()
            if next_opcode >= 8:
                continue
            buffer += part
            if len(buffer) > MAX_FRAME:
                self.close(1009)
                raise Closed()
            if fin:
                return opcode, bytes(buffer)

    def send_frame(self, opcode, payload):
        length = len(payload)
        if length < 126:
            head = struct.pack(">BB", 0x80 | opcode, length)
        elif length < 65536:
            head = struct.pack(">BBH", 0x80 | opcode, 126, length)
        else:
            head = struct.pack(">BBQ", 0x80 | opcode, 127, length)
        try:
            with self.send_lock:
                self.connection.sendall(head + payload)
        except OSError:
            pass

    def send_json(self, message):
        self.send_frame(1, json.dumps(message).encode("utf-8"))

    def close(self, code, reason=""):
        self.send_frame(8, struct.pack(">H", code) + reason.encode("utf-8")[:120])

    def send_hello(self):
        data = {"obsStudioVersion": "31.0.0", "obsWebSocketVersion": "5.5.0", "rpcVersion": 1}
        if self.server.args.password:
            data["authentication"] = {"challenge": self.challenge, "salt": self.salt}
        self.send_json({"op": 0, "d": data})

    def on_text(self, payload):
        try:
            message = json.loads(payload.decode("utf-8"))
            op = message["op"]
            data = message["d"]
        except (ValueError, KeyError, TypeError):
            self.close(CLOSE_DECODE_ERROR, "decode error")
            raise Closed()
        if op == 1:
            self.on_identify(data)
        elif op == 6:
            if not self.identified:
                self.close(CLOSE_NOT_IDENTIFIED, "not identified")
                raise Closed()
            self.on_request(data)

    def on_identify(self, data):
        password = self.server.args.password
        if password:
            expected = auth_response(password, self.salt, self.challenge)
            if data.get("authentication") != expected:
                self.server.log("wrong password")
                self.close(CLOSE_AUTH_FAILED, "Authentication failed.")
                raise Closed()
        self.identified = True
        with self.server.lock:
            self.server.clients.append(self)
        self.send_json({"op": 2, "d": {"negotiatedRpcVersion": 1}})
        self.server.log("identified")
        if self.server.args.attack:
            self.server.later(self.server.args.attack_after, self.attack)

    def attack(self):
        kind = self.server.args.attack
        self.server.log(f"attack: {kind}")
        if kind == "huge":
            filler = "x" * (2 * 1024 * 1024)
            self.send_frame(1, json.dumps({"op": 5, "d": {"eventType": "Huge", "eventData": {"x": filler}}}).encode("utf-8"))
        elif kind == "malformed":
            self.send_frame(1, b'{"op":7,"d":{"requestType":')
        elif kind == "deep":
            self.send_frame(1, b"[" * 200000)
        elif kind == "badutf8":
            self.send_frame(1, bytes([0xC0, 0x80]))
        elif kind == "flood":
            for index in range(5000):
                self.send_json({"op": 5, "d": {"eventType": "InputMuteStateChanged", "eventData": {"inputName": "Mic/Aux", "inputMuted": index % 2 == 0}}})
                self.send_json({"op": 5, "d": {"eventType": "SceneListChanged", "eventData": {"scenes": []}}})
            self.server.log("flood sent")
        elif kind == "containers":
            self.send_frame(1, b'{"op":7,"d":{"x":[' + b"{}," * 300000 + b"{}]}}")

    def on_request(self, data):
        request_type = data.get("requestType")
        request_id = data.get("requestId")
        ok, code, comment, response = self.server.handle_request(request_type, data.get("requestData") or {})
        status = {"result": ok, "code": code}
        if comment:
            status["comment"] = comment
        body = {"requestType": request_type, "requestId": request_id, "requestStatus": status}
        if response is not None:
            body["responseData"] = response
        self.send_json({"op": 7, "d": body})


def main():
    parser = argparse.ArgumentParser(description="Fake obs-websocket 5 server for testing SceneRemote through adb reverse")
    parser.add_argument("--host", default="127.0.0.1", help="address to listen on")
    parser.add_argument("--port", type=int, default=4455, help="port to listen on")
    parser.add_argument("--password", default="", help="password to require, empty for none")
    parser.add_argument("--hostile", action="store_true", help="use hostile scene and input names")
    parser.add_argument("--drop-after", type=float, default=0, help="seconds after which each client is dropped abruptly")
    parser.add_argument("--rotate-scene", type=float, default=0, help="seconds between program scene changes made by the server")
    parser.add_argument("--attack", choices=["huge", "malformed", "deep", "badutf8", "flood", "containers"], help="hostile data to send after login")
    parser.add_argument("--attack-after", type=float, default=3, help="seconds after login before the attack")
    parser.add_argument("--quiet", action="store_true", help="print nothing")
    FakeObs(parser.parse_args()).serve()


if __name__ == "__main__":
    main()
