#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
import json
import socket
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

DEFAULT_PORT = 7912


class HelperClientError(RuntimeError):
    pass


class HelperClient:
    def __init__(self, serial: str | None, port: int, auto_forward: bool) -> None:
        self.serial = serial
        self.port = port
        self.auto_forward = auto_forward

    def ensure_forward(self) -> None:
        if not self.auto_forward:
            return
        self._run_adb("forward", f"tcp:{self.port}", f"tcp:{self.port}")

    def health(self) -> dict:
        return self._request("GET", "/health")

    def command(self, payload: dict) -> dict:
        return self._request("POST", "/command", payload)

    def _run_adb(self, *args: str) -> str:
        command = ["adb"]
        if self.serial:
            command.extend(["-s", self.serial])
        command.extend(args)
        try:
            completed = subprocess.run(
                command,
                check=True,
                capture_output=True,
                text=True,
            )
        except FileNotFoundError as exc:
            raise HelperClientError("adb not found in PATH") from exc
        except subprocess.CalledProcessError as exc:
            stderr = exc.stderr.strip() or exc.stdout.strip() or "adb command failed"
            raise HelperClientError(stderr) from exc
        return completed.stdout.strip()

    def _request(self, method: str, path: str, payload: dict | None = None) -> dict:
        data = None
        headers = {}
        if payload is not None:
            data = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"

        request = urllib.request.Request(
            url=f"http://127.0.0.1:{self.port}{path}",
            data=data,
            headers=headers,
            method=method,
        )

        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            raise HelperClientError(f"HTTP {exc.code}: {body}") from exc
        except urllib.error.URLError as exc:
            raise HelperClientError(
                "Unable to reach helper app. Is the app installed, the accessibility service enabled, "
                "and adb forward configured?"
            ) from exc
        except TimeoutError as exc:
            raise HelperClientError("Timed out while waiting for helper app response") from exc
        except socket.timeout as exc:
            raise HelperClientError("Timed out while waiting for helper app response") from exc


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Desktop client for the AD Helper Android accessibility bridge.",
    )
    parser.add_argument("--serial", help="ADB device serial")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="Forwarded localhost port")
    parser.add_argument(
        "--no-forward",
        action="store_true",
        help="Skip running adb forward before sending the request",
    )
    parser.add_argument(
        "--pretty",
        action="store_true",
        help="Pretty print JSON responses",
    )

    subparsers = parser.add_subparsers(dest="command_name", required=True)

    subparsers.add_parser("health", help="Check service and server status")

    dump_tree = subparsers.add_parser("dump-tree", help="Fetch the current accessibility tree")
    dump_tree.add_argument("--output", type=Path, help="Write the JSON tree to a file")

    list_clickables = subparsers.add_parser(
        "list-clickables",
        help="List clickable or focusable nodes on the current screen",
    )
    list_clickables.add_argument("--output", type=Path, help="Write the clickable-node JSON to a file")

    click_text = subparsers.add_parser("click-text", help="Click the first node matching text")
    click_text.add_argument("text", help="Text or content description to match")
    click_text.add_argument(
        "--exact",
        action="store_true",
        help="Require exact case-insensitive match instead of contains",
    )

    click_point = subparsers.add_parser("click-point", help="Tap by screen coordinate")
    click_point.add_argument("x", type=int)
    click_point.add_argument("y", type=int)

    scroll = subparsers.add_parser("scroll", help="Scroll the current screen")
    scroll.add_argument("direction", choices=["up", "down", "left", "right"])
    scroll.add_argument(
        "--distance-ratio",
        type=float,
        default=0.55,
        help="Gesture travel ratio between 0.2 and 0.85",
    )

    subparsers.add_parser("back", help="Trigger Android global back action")

    screenshot = subparsers.add_parser("screenshot", help="Capture a screenshot")
    screenshot.add_argument(
        "--output",
        type=Path,
        default=Path("adhelper-screenshot.jpg"),
        help="Write decoded image bytes to this file",
    )

    return parser


def write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def print_json(payload: dict, pretty: bool) -> None:
    if pretty:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        print(json.dumps(payload, ensure_ascii=False))


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    client = HelperClient(serial=args.serial, port=args.port, auto_forward=not args.no_forward)

    try:
        client.ensure_forward()

        if args.command_name == "health":
            response = client.health()
            print_json(response, args.pretty)
            return 0 if response.get("ok") else 1

        if args.command_name == "dump-tree":
            response = client.command({"command": "dump_tree"})
            if args.output:
                write_json(args.output, response)
                print(f"Wrote tree JSON to {args.output}")
            else:
                print_json(response, args.pretty)
            return 0 if response.get("ok") else 1

        if args.command_name == "list-clickables":
            response = client.command({"command": "list_clickables"})
            if args.output:
                write_json(args.output, response)
                print(f"Wrote clickable-node JSON to {args.output}")
            else:
                print_json(response, args.pretty)
            return 0 if response.get("ok") else 1

        if args.command_name == "click-text":
            response = client.command(
                {
                    "command": "click_text",
                    "text": args.text,
                    "exact": args.exact,
                }
            )
            print_json(response, args.pretty)
            return 0 if response.get("ok") else 1

        if args.command_name == "click-point":
            response = client.command(
                {
                    "command": "click_point",
                    "x": args.x,
                    "y": args.y,
                }
            )
            print_json(response, args.pretty)
            return 0 if response.get("ok") else 1

        if args.command_name == "scroll":
            response = client.command(
                {
                    "command": "scroll",
                    "direction": args.direction,
                    "distanceRatio": args.distance_ratio,
                }
            )
            print_json(response, args.pretty)
            return 0 if response.get("ok") else 1

        if args.command_name == "back":
            response = client.command({"command": "back"})
            print_json(response, args.pretty)
            return 0 if response.get("ok") else 1

        if args.command_name == "screenshot":
            response = client.command({"command": "screenshot"})
            if not response.get("ok"):
                print_json(response, args.pretty)
                return 1

            image_base64 = response["result"]["imageBase64"]
            image_bytes = base64.b64decode(image_base64)
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_bytes(image_bytes)

            printable = dict(response)
            printable["result"] = dict(response["result"])
            printable["result"].pop("imageBase64", None)
            printable["result"]["outputPath"] = str(args.output)
            print_json(printable, args.pretty)
            return 0

        parser.error("Unknown command")
        return 2
    except HelperClientError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
