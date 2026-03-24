#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
import sys
import time
from pathlib import Path

from helper_client import DEFAULT_PORT, HelperClient, HelperClientError

BLOCKED_LABEL_PATTERN = re.compile(
    r"(保存|删除|移除|关闭|退出|解绑|禁用|开通|支付|购买|下单|提交|确定设置|开始断食|清空|分享|星期|今日|日历|三月|2026-|\d{4}年\d{1,2}月\d{1,2}日)",
    re.IGNORECASE,
)

ALLOWED_ANALYSIS_PATTERN = re.compile(
    r"(热量缺口|宏营养素|矿物质|维生素|饮水量|运动量|第 \d+ 个标签)",
    re.IGNORECASE,
)


def slugify(value: str) -> str:
    value = re.sub(r"\s+", "-", value.strip())
    value = re.sub(r"[^0-9A-Za-z\u4e00-\u9fff._-]+", "-", value)
    value = re.sub(r"-{2,}", "-", value).strip("-")
    return value or "page"


def write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def iter_texts(node: dict) -> list[str]:
    texts: list[str] = []
    stack = [node]
    while stack:
        current = stack.pop()
        if not isinstance(current, dict):
            continue
        for key in ("text", "contentDescription", "viewIdResourceName"):
            value = current.get(key)
            if value:
                texts.append(str(value))
        stack.extend(reversed(current.get("children") or []))
    return texts


def node_label(node: dict) -> str:
    for key in ("text", "contentDescription", "viewIdResourceName", "className"):
        value = node.get(key)
        if value:
            return str(value).replace("\n", " ").strip()
    return ""


def fingerprint(tree_payload: dict, clickables_payload: dict) -> str:
    result = tree_payload["result"]
    texts = iter_texts(result["tree"])
    clickables = clickables_payload["result"]["clickables"]
    compact = {
        "package": result.get("packageName"),
        "texts": texts[:24],
        "clickables": [
            {
                "label": node_label(node),
                "centerX": node.get("centerX"),
                "centerY": node.get("centerY"),
            }
            for node in clickables[:40]
        ],
    }
    return hashlib.sha1(
        json.dumps(compact, ensure_ascii=False, sort_keys=True).encode("utf-8")
    ).hexdigest()[:12]


def should_click(node: dict, package_name: str) -> bool:
    if node.get("packageName") != package_name:
        return False
    if not node.get("enabled", True):
        return False

    label = node_label(node)
    raw_text = str(node.get("text") or "").strip()
    raw_desc = str(node.get("contentDescription") or "").strip()
    if not raw_text and not raw_desc:
        return False
    if BLOCKED_LABEL_PATTERN.search(label):
        return False

    if "营养分析" in label:
        return False
    if node.get("className") == "android.widget.ImageView" and not raw_desc:
        return False
    if ("第 " in label and "个标签" in label) or ALLOWED_ANALYSIS_PATTERN.search(label):
        return True

    bounds = node.get("bounds") or {}
    if bounds.get("top", 0) < 120:
        return False
    return True


class XiaokaCrawler:
    def __init__(
        self,
        client: HelperClient,
        output_dir: Path,
        package_name: str,
        max_depth: int,
        max_clicks_per_page: int,
        settle_seconds: float,
    ) -> None:
        self.client = client
        self.output_dir = output_dir
        self.package_name = package_name
        self.max_depth = max_depth
        self.max_clicks_per_page = max_clicks_per_page
        self.settle_seconds = settle_seconds
        self.page_counter = 0
        self.visited_pages: set[str] = set()
        self.manifest: list[dict] = []

    def launch_app(self) -> None:
        self.client._run_adb("shell", "monkey", "-p", self.package_name, "-c", "android.intent.category.LAUNCHER", "1")
        time.sleep(self.settle_seconds)

    def restore_home(self) -> None:
        self.launch_app()

    def capture_page(self, trail: list[str]) -> tuple[dict, dict, dict, str, Path]:
        tree_payload = self.safe_command({"command": "dump_tree"})
        clickables_payload = self.safe_command({"command": "list_clickables", "visibleOnly": True})
        screenshot_payload = self.safe_command({"command": "screenshot"})
        page_fp = fingerprint(tree_payload, clickables_payload)
        package_name = tree_payload["result"].get("packageName") or "unknown"
        label = slugify("__".join(trail[-3:]) or package_name)
        stem = f"{self.page_counter:03d}_{label}_{page_fp}"
        self.page_counter += 1
        page_dir = self.output_dir / stem
        page_dir.mkdir(parents=True, exist_ok=True)

        write_json(page_dir / "tree.json", tree_payload)
        write_json(page_dir / "clickables.json", clickables_payload)

        image_bytes = base64.b64decode(screenshot_payload["result"]["imageBase64"])
        (page_dir / "screen.jpg").write_bytes(image_bytes)

        meta = {
            "fingerprint": page_fp,
            "trail": trail,
            "packageName": package_name,
            "pageDir": str(page_dir),
            "clickableCount": clickables_payload["result"]["count"],
        }
        write_json(page_dir / "meta.json", meta)
        self.manifest.append(meta)
        write_json(self.output_dir / "manifest.json", {"pages": self.manifest})
        return tree_payload, clickables_payload, meta, page_fp, page_dir

    def crawl(self) -> None:
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.launch_app()
        self._explore(depth=0, trail=["home"])

    def safe_command(self, payload: dict, retries: int = 2) -> dict:
        last_error: HelperClientError | None = None
        for attempt in range(retries + 1):
            try:
                return self.client.command(payload)
            except HelperClientError as exc:
                last_error = exc
                if attempt >= retries:
                    break
                time.sleep(self.settle_seconds)
        assert last_error is not None
        raise last_error

    def _explore(self, depth: int, trail: list[str]) -> None:
        try:
            tree_payload, clickables_payload, _meta, page_fp, _page_dir = self.capture_page(trail)
        except HelperClientError:
            return
        if page_fp in self.visited_pages:
            return
        self.visited_pages.add(page_fp)

        package_name = tree_payload["result"].get("packageName")
        if package_name != self.package_name or depth >= self.max_depth:
            return

        clickables = clickables_payload["result"]["clickables"]
        seen_candidates: set[tuple[str, int, int]] = set()
        clicked = 0

        for node in clickables:
            if clicked >= self.max_clicks_per_page:
                break
            if not should_click(node, self.package_name):
                continue

            label = node_label(node)
            signature = (label, int(node["centerX"]), int(node["centerY"]))
            if signature in seen_candidates:
                continue
            seen_candidates.add(signature)

            try:
                self.safe_command(
                    {
                        "command": "click_point",
                        "x": int(node["centerX"]),
                        "y": int(node["centerY"]),
                    }
                )
            except HelperClientError:
                continue
            time.sleep(self.settle_seconds)

            try:
                next_tree = self.safe_command({"command": "dump_tree"})
                next_clickables = self.safe_command({"command": "list_clickables", "visibleOnly": True})
            except HelperClientError:
                try:
                    self.safe_command({"command": "back"})
                except HelperClientError:
                    pass
                time.sleep(self.settle_seconds)
                continue
            next_fp = fingerprint(next_tree, next_clickables)
            next_package = next_tree["result"].get("packageName")

            if next_package != self.package_name:
                self.restore_home()
                continue

            if next_fp == page_fp:
                continue

            if next_package == self.package_name:
                clicked += 1
                self._explore(depth + 1, trail + [label])

            try:
                self.safe_command({"command": "back"})
            except HelperClientError:
                self.restore_home()
                return
            time.sleep(self.settle_seconds)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Conservative page crawler for the Xiaoka Health app.",
    )
    parser.add_argument("--serial", help="ADB device serial")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="Forwarded localhost port")
    parser.add_argument(
        "--no-forward",
        action="store_true",
        help="Skip running adb forward before sending the request",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("tmp/xiaoka"),
        help="Directory for screenshots and metadata",
    )
    parser.add_argument(
        "--package",
        default="com.calai.calmate",
        help="Target app package name",
    )
    parser.add_argument("--depth", "--max-depth", dest="max_depth", type=int, default=2)
    parser.add_argument("--max-clicks-per-page", type=int, default=3)
    parser.add_argument("--settle-seconds", type=float, default=1.2)
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    client = HelperClient(serial=args.serial, port=args.port, auto_forward=not args.no_forward)

    try:
        client.ensure_forward()
        crawler = XiaokaCrawler(
            client=client,
            output_dir=args.output_dir,
            package_name=args.package,
            max_depth=args.max_depth,
            max_clicks_per_page=args.max_clicks_per_page,
            settle_seconds=args.settle_seconds,
        )
        crawler.crawl()
        print(
            json.dumps(
                {
                    "ok": True,
                    "pages": len(crawler.visited_pages),
                    "outputDir": str(args.output_dir),
                    "manifest": str(args.output_dir / "manifest.json"),
                },
                ensure_ascii=False,
            )
        )
        return 0
    except HelperClientError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    finally:
        try:
            client._run_adb("shell", "monkey", "-p", args.package, "-c", "android.intent.category.LAUNCHER", "1")
        except Exception:
            pass


if __name__ == "__main__":
    raise SystemExit(main())
