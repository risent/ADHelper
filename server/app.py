from __future__ import annotations

import asyncio
import base64
import json
import os
import sqlite3
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from fastapi import Depends, FastAPI, HTTPException, Request, WebSocket, WebSocketDisconnect, status
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse
from pydantic import BaseModel, Field

BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = BASE_DIR / "data"
ARTIFACT_DIR = DATA_DIR / "artifacts"
DB_PATH = DATA_DIR / "adhelper.db"
SHARED_TOKEN = os.environ.get("ADHELPER_SHARED_TOKEN", "change-me")

app = FastAPI(title="ADHelper Server", version="1.0.0")


@dataclass
class DeviceConnection:
    device_id: str
    websocket: WebSocket
    dispatch_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    pending_results: dict[str, asyncio.Future[dict[str, Any]]] = field(default_factory=dict)


connections: dict[str, DeviceConnection] = {}
workflow_tasks: dict[str, asyncio.Task[None]] = {}


class WorkflowStepModel(BaseModel):
    name: str
    payload: dict[str, Any]
    timeoutMs: int | None = None


class WorkflowModel(BaseModel):
    name: str = Field(min_length=1)
    description: str = ""
    steps: list[WorkflowStepModel]


class WorkflowRunModel(BaseModel):
    workflowId: str


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def ensure_dirs() -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    ARTIFACT_DIR.mkdir(parents=True, exist_ok=True)


def connect_db() -> sqlite3.Connection:
    ensure_dirs()
    connection = sqlite3.connect(DB_PATH)
    connection.row_factory = sqlite3.Row
    return connection


def init_db() -> None:
    with connect_db() as db:
        db.executescript(
            """
            CREATE TABLE IF NOT EXISTS devices (
                device_id TEXT PRIMARY KEY,
                online INTEGER NOT NULL DEFAULT 0,
                busy INTEGER NOT NULL DEFAULT 0,
                server_url TEXT,
                package_name TEXT,
                device_model TEXT,
                sdk_int INTEGER,
                app_version TEXT,
                last_seen_at TEXT,
                last_error TEXT,
                last_snapshot_json TEXT
            );

            CREATE TABLE IF NOT EXISTS jobs (
                job_id TEXT PRIMARY KEY,
                device_id TEXT NOT NULL,
                kind TEXT NOT NULL,
                workflow_template_id TEXT,
                command_name TEXT,
                status TEXT NOT NULL,
                request_id TEXT,
                response_json TEXT,
                error_code TEXT,
                error_message TEXT,
                artifact_count INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                completed_at TEXT
            );

            CREATE TABLE IF NOT EXISTS job_steps (
                step_id TEXT PRIMARY KEY,
                job_id TEXT NOT NULL,
                step_index INTEGER NOT NULL,
                name TEXT NOT NULL,
                command_name TEXT,
                request_id TEXT,
                status TEXT NOT NULL,
                response_json TEXT,
                error_code TEXT,
                error_message TEXT,
                artifact_count INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                completed_at TEXT
            );

            CREATE TABLE IF NOT EXISTS workflow_templates (
                workflow_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS workflow_template_steps (
                step_id TEXT PRIMARY KEY,
                workflow_id TEXT NOT NULL,
                step_index INTEGER NOT NULL,
                name TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                timeout_ms INTEGER
            );

            CREATE TABLE IF NOT EXISTS artifacts (
                artifact_id TEXT PRIMARY KEY,
                job_id TEXT NOT NULL,
                step_id TEXT,
                kind TEXT NOT NULL,
                file_path TEXT NOT NULL,
                mime_type TEXT,
                size_bytes INTEGER NOT NULL,
                created_at TEXT NOT NULL
            );
            """
        )


def verify_api_token(request: Request) -> None:
    authorization = request.headers.get("authorization", "")
    expected = f"Bearer {SHARED_TOKEN}"
    if authorization != expected:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token")


def row_to_dict(row: sqlite3.Row | None) -> dict[str, Any] | None:
    return dict(row) if row is not None else None


def json_loads(value: str | None, fallback: Any) -> Any:
    if not value:
        return fallback
    try:
        return json.loads(value)
    except json.JSONDecodeError:
        return fallback


def get_device(device_id: str) -> dict[str, Any] | None:
    with connect_db() as db:
        row = db.execute("SELECT * FROM devices WHERE device_id = ?", (device_id,)).fetchone()
        if row is None:
            return None
        payload = dict(row)
        payload["online"] = bool(payload["online"])
        payload["busy"] = bool(payload["busy"])
        payload["last_snapshot"] = json_loads(payload.pop("last_snapshot_json", None), {})
        return payload


def list_devices() -> list[dict[str, Any]]:
    with connect_db() as db:
        rows = db.execute(
            "SELECT * FROM devices ORDER BY online DESC, last_seen_at DESC, device_id ASC"
        ).fetchall()
        devices = []
        for row in rows:
            payload = dict(row)
            payload["online"] = bool(payload["online"])
            payload["busy"] = bool(payload["busy"])
            payload["last_snapshot"] = json_loads(payload.pop("last_snapshot_json", None), {})
            devices.append(payload)
        return devices


def set_device_online(device_id: str, hello: dict[str, Any]) -> None:
    now = utc_now()
    health = hello.get("health", {})
    with connect_db() as db:
        db.execute(
            """
            INSERT INTO devices (
                device_id, online, busy, server_url, package_name, device_model, sdk_int, app_version,
                last_seen_at, last_error, last_snapshot_json
            ) VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, NULL, ?)
            ON CONFLICT(device_id) DO UPDATE SET
                online = 1,
                busy = excluded.busy,
                server_url = excluded.server_url,
                package_name = excluded.package_name,
                device_model = excluded.device_model,
                sdk_int = excluded.sdk_int,
                app_version = excluded.app_version,
                last_seen_at = excluded.last_seen_at,
                last_error = NULL,
                last_snapshot_json = excluded.last_snapshot_json
            """,
            (
                device_id,
                1 if health.get("busy") else 0,
                health.get("remoteServerUrl"),
                hello.get("packageName"),
                hello.get("deviceModel"),
                hello.get("sdkInt"),
                hello.get("appVersion"),
                now,
                json.dumps(health, ensure_ascii=False),
            ),
        )


def update_device_status(device_id: str, payload: dict[str, Any], error_message: str | None = None) -> None:
    now = utc_now()
    with connect_db() as db:
        db.execute(
            """
            UPDATE devices
            SET online = 1,
                busy = ?,
                last_seen_at = ?,
                last_error = ?,
                last_snapshot_json = ?
            WHERE device_id = ?
            """,
            (
                1 if payload.get("busy") else 0,
                now,
                error_message,
                json.dumps(payload, ensure_ascii=False),
                device_id,
            ),
        )


def mark_device_offline(device_id: str, error_message: str | None = None) -> None:
    with connect_db() as db:
        db.execute(
            """
            UPDATE devices
            SET online = 0, busy = 0, last_seen_at = ?, last_error = ?
            WHERE device_id = ?
            """,
            (utc_now(), error_message, device_id),
        )


def create_job(
    *,
    device_id: str,
    kind: str,
    status_value: str,
    command_name: str | None = None,
    workflow_template_id: str | None = None,
) -> str:
    job_id = str(uuid.uuid4())
    now = utc_now()
    with connect_db() as db:
        db.execute(
            """
            INSERT INTO jobs (
                job_id, device_id, kind, workflow_template_id, command_name, status, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (job_id, device_id, kind, workflow_template_id, command_name, status_value, now, now),
        )
    return job_id


def update_job(
    job_id: str,
    *,
    status_value: str,
    request_id: str | None = None,
    response: dict[str, Any] | None = None,
    error_code: str | None = None,
    error_message: str | None = None,
    artifact_count: int | None = None,
    completed: bool = False,
) -> None:
    now = utc_now()
    with connect_db() as db:
        db.execute(
            """
            UPDATE jobs
            SET status = ?,
                request_id = COALESCE(?, request_id),
                response_json = COALESCE(?, response_json),
                error_code = ?,
                error_message = ?,
                artifact_count = COALESCE(?, artifact_count),
                updated_at = ?,
                completed_at = CASE WHEN ? THEN ? ELSE completed_at END
            WHERE job_id = ?
            """,
            (
                status_value,
                request_id,
                json.dumps(response, ensure_ascii=False) if response is not None else None,
                error_code,
                error_message,
                artifact_count,
                now,
                1 if completed else 0,
                now,
                job_id,
            ),
        )


def create_job_step(
    *,
    job_id: str,
    step_index: int,
    name: str,
    command_name: str,
) -> str:
    step_id = str(uuid.uuid4())
    now = utc_now()
    with connect_db() as db:
        db.execute(
            """
            INSERT INTO job_steps (
                step_id, job_id, step_index, name, command_name, status, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, 'pending', ?, ?)
            """,
            (step_id, job_id, step_index, name, command_name, now, now),
        )
    return step_id


def update_job_step(
    step_id: str,
    *,
    status_value: str,
    request_id: str | None = None,
    response: dict[str, Any] | None = None,
    error_code: str | None = None,
    error_message: str | None = None,
    artifact_count: int | None = None,
    completed: bool = False,
) -> None:
    now = utc_now()
    with connect_db() as db:
        db.execute(
            """
            UPDATE job_steps
            SET status = ?,
                request_id = COALESCE(?, request_id),
                response_json = COALESCE(?, response_json),
                error_code = ?,
                error_message = ?,
                artifact_count = COALESCE(?, artifact_count),
                updated_at = ?,
                completed_at = CASE WHEN ? THEN ? ELSE completed_at END
            WHERE step_id = ?
            """,
            (
                status_value,
                request_id,
                json.dumps(response, ensure_ascii=False) if response is not None else None,
                error_code,
                error_message,
                artifact_count,
                now,
                1 if completed else 0,
                now,
                step_id,
            ),
        )


def list_jobs(limit: int = 20) -> list[dict[str, Any]]:
    with connect_db() as db:
        rows = db.execute(
            "SELECT * FROM jobs ORDER BY created_at DESC LIMIT ?",
            (limit,),
        ).fetchall()
        jobs = []
        for row in rows:
            payload = dict(row)
            payload["response"] = json_loads(payload.pop("response_json", None), None)
            jobs.append(payload)
        return jobs


def get_job(job_id: str) -> dict[str, Any] | None:
    with connect_db() as db:
        row = db.execute("SELECT * FROM jobs WHERE job_id = ?", (job_id,)).fetchone()
        if row is None:
            return None
        steps = db.execute(
            "SELECT * FROM job_steps WHERE job_id = ? ORDER BY step_index ASC",
            (job_id,),
        ).fetchall()
        artifacts = db.execute(
            "SELECT * FROM artifacts WHERE job_id = ? ORDER BY created_at ASC",
            (job_id,),
        ).fetchall()
        payload = dict(row)
        payload["response"] = json_loads(payload.pop("response_json", None), None)
        payload["steps"] = [
            {
                **dict(step),
                "response": json_loads(step["response_json"], None),
            }
            for step in steps
        ]
        for step in payload["steps"]:
            step.pop("response_json", None)
        payload["artifacts"] = [dict(artifact) for artifact in artifacts]
        return payload


def list_workflows() -> list[dict[str, Any]]:
    with connect_db() as db:
        workflows = []
        rows = db.execute(
            "SELECT * FROM workflow_templates ORDER BY updated_at DESC, name ASC"
        ).fetchall()
        for row in rows:
            workflow_id = row["workflow_id"]
            steps = db.execute(
                """
                SELECT * FROM workflow_template_steps
                WHERE workflow_id = ?
                ORDER BY step_index ASC
                """,
                (workflow_id,),
            ).fetchall()
            workflows.append(
                {
                    **dict(row),
                    "steps": [
                        {
                            "stepId": step["step_id"],
                            "stepIndex": step["step_index"],
                            "name": step["name"],
                            "timeoutMs": step["timeout_ms"],
                            "payload": json_loads(step["payload_json"], {}),
                        }
                        for step in steps
                    ],
                }
            )
        return workflows


def get_workflow(workflow_id: str) -> dict[str, Any] | None:
    for workflow in list_workflows():
        if workflow["workflow_id"] == workflow_id:
            return workflow
    return None


def save_workflow(workflow: WorkflowModel, workflow_id: str | None = None) -> str:
    actual_id = workflow_id or str(uuid.uuid4())
    now = utc_now()
    with connect_db() as db:
        db.execute(
            """
            INSERT INTO workflow_templates (workflow_id, name, description, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(workflow_id) DO UPDATE SET
                name = excluded.name,
                description = excluded.description,
                updated_at = excluded.updated_at
            """,
            (actual_id, workflow.name, workflow.description, now, now),
        )
        db.execute("DELETE FROM workflow_template_steps WHERE workflow_id = ?", (actual_id,))
        for index, step in enumerate(workflow.steps):
            db.execute(
                """
                INSERT INTO workflow_template_steps (
                    step_id, workflow_id, step_index, name, payload_json, timeout_ms
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                (
                    str(uuid.uuid4()),
                    actual_id,
                    index,
                    step.name,
                    json.dumps(step.payload, ensure_ascii=False),
                    step.timeoutMs,
                ),
            )
    return actual_id


def store_artifact(
    *,
    job_id: str,
    step_id: str | None,
    mime_type: str,
    raw_bytes: bytes,
) -> dict[str, Any]:
    artifact_id = str(uuid.uuid4())
    suffix = {
        "image/jpeg": ".jpg",
        "image/png": ".png",
    }.get(mime_type, ".bin")
    relative_path = Path(job_id) / f"{artifact_id}{suffix}"
    output_path = ARTIFACT_DIR / relative_path
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(raw_bytes)
    with connect_db() as db:
        db.execute(
            """
            INSERT INTO artifacts (artifact_id, job_id, step_id, kind, file_path, mime_type, size_bytes, created_at)
            VALUES (?, ?, ?, 'image', ?, ?, ?, ?)
            """,
            (
                artifact_id,
                job_id,
                step_id,
                str(relative_path),
                mime_type,
                len(raw_bytes),
                utc_now(),
            ),
        )
    return {
        "artifactId": artifact_id,
        "artifactUrl": f"/artifacts/{artifact_id}",
        "mimeType": mime_type,
        "sizeBytes": len(raw_bytes),
    }


def strip_artifacts(value: Any, *, job_id: str, step_id: str | None = None) -> tuple[Any, int]:
    artifact_count = 0
    if isinstance(value, dict):
        if isinstance(value.get("imageBase64"), str):
            mime_type = value.get("mimeType") or "image/jpeg"
            try:
                raw_bytes = base64.b64decode(value["imageBase64"])
                artifact = store_artifact(job_id=job_id, step_id=step_id, mime_type=mime_type, raw_bytes=raw_bytes)
                sanitized = dict(value)
                sanitized.pop("imageBase64", None)
                sanitized.update(artifact)
                return sanitized, 1
            except Exception:
                sanitized = dict(value)
                sanitized.pop("imageBase64", None)
                sanitized["artifactError"] = "Failed to decode imageBase64"
                return sanitized, 0

        sanitized: dict[str, Any] = {}
        for key, item in value.items():
            sanitized_item, child_count = strip_artifacts(item, job_id=job_id, step_id=step_id)
            sanitized[key] = sanitized_item
            artifact_count += child_count
        return sanitized, artifact_count

    if isinstance(value, list):
        sanitized_list = []
        for item in value:
            sanitized_item, child_count = strip_artifacts(item, job_id=job_id, step_id=step_id)
            sanitized_list.append(sanitized_item)
            artifact_count += child_count
        return sanitized_list, artifact_count

    return value, 0


def build_error_payload(error_code: str, message: str, *, request_id: str | None = None) -> dict[str, Any]:
    return {
        "ok": False,
        "schemaVersion": 2,
        "requestId": request_id or str(uuid.uuid4()),
        "errorCode": error_code,
        "error": message,
    }


def default_timeout_ms(payload: dict[str, Any]) -> int:
    command = str(payload.get("command", "")).lower()
    if command in {"click_text", "click_node", "click_point", "back"}:
        return 5_000
    if command == "scroll":
        return 8_000
    if command == "screenshot":
        return 12_000
    if command in {"dump_tree", "list_clickables", "snapshot"}:
        return 15_000
    if command == "wait_for_stable_tree":
        return max(500, min(60_000, int(payload.get("timeoutMs", 10_000)))) + 2_000
    return 10_000


async def dispatch_command(
    device_id: str,
    payload: dict[str, Any],
    timeout_ms: int | None = None,
) -> tuple[str, int, dict[str, Any]]:
    connection = connections.get(device_id)
    if connection is None:
        raise HTTPException(status_code=503, detail="Device is offline")
    if connection.dispatch_lock.locked():
        raise HTTPException(status_code=409, detail="Device is busy")

    request_id = str(uuid.uuid4())
    timeout = (timeout_ms or default_timeout_ms(payload)) / 1000
    loop = asyncio.get_running_loop()
    future: asyncio.Future[dict[str, Any]] = loop.create_future()

    async with connection.dispatch_lock:
        connection.pending_results[request_id] = future
        update_device_status(device_id, {"busy": True}, None)
        try:
            await connection.websocket.send_text(
                json.dumps(
                    {
                        "type": "server.command.dispatch",
                        "requestId": request_id,
                        "timeoutMs": timeout_ms or default_timeout_ms(payload),
                        "payload": payload,
                    },
                    ensure_ascii=False,
                )
            )
            result = await asyncio.wait_for(future, timeout=timeout + 2.0)
            return request_id, int(result.get("statusCode", 200)), result.get("response") or {}
        except asyncio.TimeoutError as exc:
            raise HTTPException(status_code=504, detail="Timed out waiting for device response") from exc
        finally:
            connection.pending_results.pop(request_id, None)
            update_device_status(device_id, {"busy": False}, None)


async def run_workflow_job(job_id: str, device_id: str, workflow: dict[str, Any]) -> None:
    update_job(job_id, status_value="running")
    overall_ok = True
    last_error_code = None
    last_error_message = None

    for step in workflow["steps"]:
        step_id = create_job_step(
            job_id=job_id,
            step_index=step["stepIndex"],
            name=step["name"],
            command_name=step["payload"].get("command", "unknown"),
        )
        update_job_step(step_id, status_value="running")
        try:
            request_id, status_code, response = await dispatch_command(
                device_id,
                step["payload"],
                step.get("timeoutMs"),
            )
            sanitized, artifact_count = strip_artifacts(response, job_id=job_id, step_id=step_id)
            if response.get("ok"):
                update_job_step(
                    step_id,
                    status_value="succeeded",
                    request_id=request_id,
                    response=sanitized,
                    artifact_count=artifact_count,
                    completed=True,
                )
            else:
                overall_ok = False
                last_error_code = response.get("errorCode")
                last_error_message = response.get("error")
                update_job_step(
                    step_id,
                    status_value="failed",
                    request_id=request_id,
                    response=sanitized,
                    error_code=last_error_code,
                    error_message=last_error_message,
                    artifact_count=artifact_count,
                    completed=True,
                )
                break
        except HTTPException as exc:
            overall_ok = False
            last_error_code = "WORKFLOW_STEP_FAILED"
            last_error_message = exc.detail
            update_job_step(
                step_id,
                status_value="failed",
                error_code=last_error_code,
                error_message=last_error_message,
                completed=True,
            )
            break

    update_job(
        job_id,
        status_value="succeeded" if overall_ok else "failed",
        error_code=last_error_code,
        error_message=last_error_message,
        completed=True,
    )
    workflow_tasks.pop(job_id, None)


@app.on_event("startup")
async def on_startup() -> None:
    init_db()


@app.get("/health")
async def server_health() -> dict[str, Any]:
    return {
        "ok": True,
        "serverTime": utc_now(),
        "connectedDevices": len(connections),
    }


@app.get("/", response_class=HTMLResponse)
async def console() -> str:
    return """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>ADHelper Console</title>
  <style>
    body { font-family: sans-serif; margin: 24px; background: #f6f7f9; color: #1a1d21; }
    h1, h2 { margin-bottom: 8px; }
    .grid { display: grid; grid-template-columns: 1.1fr 1fr; gap: 20px; }
    .card { background: white; padding: 16px; border-radius: 12px; box-shadow: 0 1px 4px rgba(0,0,0,0.08); }
    textarea, input, select, button { width: 100%; box-sizing: border-box; margin-top: 8px; padding: 10px; }
    pre { background: #111827; color: #e5e7eb; padding: 12px; border-radius: 8px; overflow: auto; }
    table { width: 100%; border-collapse: collapse; }
    td, th { text-align: left; border-bottom: 1px solid #e5e7eb; padding: 8px 4px; vertical-align: top; }
  </style>
</head>
<body>
  <h1>ADHelper Console</h1>
  <div class="card">
    <label>Bearer Token</label>
    <input id="token" type="password" placeholder="ADHELPER_SHARED_TOKEN" />
    <button onclick="saveToken()">Save Token</button>
  </div>
  <div class="grid" style="margin-top:20px;">
    <div class="card">
      <h2>Devices</h2>
      <button onclick="loadDevices()">Refresh Devices</button>
      <table id="devicesTable"></table>
    </div>
    <div class="card">
      <h2>Manual Command</h2>
      <label>Device</label>
      <select id="deviceSelect"></select>
      <label>Payload JSON</label>
      <textarea id="commandPayload" rows="10">{"command":"snapshot"}</textarea>
      <button onclick="sendCommand()">Send Command</button>
      <pre id="commandResult"></pre>
    </div>
  </div>
  <div class="grid" style="margin-top:20px;">
    <div class="card">
      <h2>Workflow Templates</h2>
      <button onclick="loadWorkflows()">Refresh Workflows</button>
      <label>Workflow ID (fill to update existing)</label>
      <input id="workflowId" placeholder="optional existing workflow id" />
      <label>Name</label>
      <input id="workflowName" placeholder="Open and snapshot" />
      <label>Description</label>
      <input id="workflowDescription" placeholder="Sequential command template" />
      <label>Steps JSON</label>
      <textarea id="workflowSteps" rows="12">[
  {"name":"snapshot","payload":{"command":"snapshot"}}
]</textarea>
      <button onclick="saveWorkflow()">Create / Update Workflow</button>
      <pre id="workflowList"></pre>
    </div>
    <div class="card">
      <h2>Run Workflow</h2>
      <label>Device</label>
      <select id="runDeviceSelect"></select>
      <label>Workflow</label>
      <select id="workflowSelect"></select>
      <button onclick="runWorkflow()">Start Workflow</button>
      <label>Run ID</label>
      <input id="runId" placeholder="filled automatically" />
      <button onclick="loadRun()">Load Run</button>
      <pre id="runResult"></pre>
    </div>
  </div>
  <div class="card" style="margin-top:20px;">
    <h2>Recent Jobs</h2>
    <button onclick="loadJobs()">Refresh Jobs</button>
    <pre id="jobsResult"></pre>
  </div>
  <script>
    const tokenInput = document.getElementById("token");
    tokenInput.value = localStorage.getItem("adhelperToken") || "";

    function saveToken() {
      localStorage.setItem("adhelperToken", tokenInput.value.trim());
    }

    async function api(path, options = {}) {
      const token = localStorage.getItem("adhelperToken") || "";
      const headers = Object.assign({}, options.headers || {}, token ? {Authorization: `Bearer ${token}`} : {});
      const response = await fetch(path, Object.assign({}, options, {headers}));
      const text = await response.text();
      let payload = text;
      try { payload = JSON.parse(text); } catch (_) {}
      if (!response.ok) {
        throw new Error(typeof payload === "string" ? payload : JSON.stringify(payload, null, 2));
      }
      return payload;
    }

    async function loadDevices() {
      const devices = await api("/api/devices");
      const table = document.getElementById("devicesTable");
      table.innerHTML = "<tr><th>Device</th><th>Status</th><th>Last Seen</th><th>Model</th></tr>" +
        devices.map(device => `<tr><td>${device.device_id}</td><td>${device.online ? "online" : "offline"}${device.busy ? " / busy" : ""}</td><td>${device.last_seen_at || ""}</td><td>${device.device_model || ""}</td></tr>`).join("");
      const selects = [document.getElementById("deviceSelect"), document.getElementById("runDeviceSelect")];
      for (const select of selects) {
        select.innerHTML = devices.map(device => `<option value="${device.device_id}">${device.device_id}</option>`).join("");
      }
    }

    async function sendCommand() {
      const deviceId = document.getElementById("deviceSelect").value;
      const payload = JSON.parse(document.getElementById("commandPayload").value);
      const result = await api(`/api/devices/${encodeURIComponent(deviceId)}/commands`, {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify(payload),
      });
      document.getElementById("commandResult").textContent = JSON.stringify(result, null, 2);
      await loadJobs();
    }

    async function loadWorkflows() {
      const workflows = await api("/api/workflows");
      document.getElementById("workflowList").textContent = JSON.stringify(workflows, null, 2);
      document.getElementById("workflowSelect").innerHTML = workflows.map(
        workflow => `<option value="${workflow.workflow_id}">${workflow.name}</option>`
      ).join("");
    }

    async function saveWorkflow() {
      const workflowId = document.getElementById("workflowId").value.trim();
      const payload = {
        name: document.getElementById("workflowName").value,
        description: document.getElementById("workflowDescription").value,
        steps: JSON.parse(document.getElementById("workflowSteps").value),
      };
      const path = workflowId ? `/api/workflows/${encodeURIComponent(workflowId)}` : "/api/workflows";
      const method = workflowId ? "PUT" : "POST";
      const result = await api(path, {
        method,
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify(payload),
      });
      document.getElementById("workflowList").textContent = JSON.stringify(result, null, 2);
      await loadWorkflows();
    }

    async function runWorkflow() {
      const deviceId = document.getElementById("runDeviceSelect").value;
      const workflowId = document.getElementById("workflowSelect").value;
      const result = await api(`/api/devices/${encodeURIComponent(deviceId)}/workflow-runs`, {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({workflowId}),
      });
      document.getElementById("runId").value = result.runId;
      document.getElementById("runResult").textContent = JSON.stringify(result, null, 2);
      await loadJobs();
    }

    async function loadRun() {
      const runId = document.getElementById("runId").value.trim();
      const result = await api(`/api/workflow-runs/${encodeURIComponent(runId)}`);
      document.getElementById("runResult").textContent = JSON.stringify(result, null, 2);
      await loadJobs();
    }

    async function loadJobs() {
      const jobs = await api("/api/jobs");
      document.getElementById("jobsResult").textContent = JSON.stringify(jobs, null, 2);
    }

    loadDevices().catch(console.error);
    loadWorkflows().catch(console.error);
    loadJobs().catch(console.error);
  </script>
</body>
</html>
"""


@app.get("/artifacts/{artifact_id}")
async def get_artifact(artifact_id: str) -> FileResponse:
    with connect_db() as db:
        row = db.execute(
            "SELECT file_path, mime_type FROM artifacts WHERE artifact_id = ?",
            (artifact_id,),
        ).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="Artifact not found")
    path = ARTIFACT_DIR / row["file_path"]
    if not path.exists():
        raise HTTPException(status_code=404, detail="Artifact file missing")
    return FileResponse(path, media_type=row["mime_type"] or "application/octet-stream")


@app.get("/api/devices", dependencies=[Depends(verify_api_token)])
async def api_list_devices() -> list[dict[str, Any]]:
    return list_devices()


@app.get("/api/devices/{device_id}", dependencies=[Depends(verify_api_token)])
async def api_get_device(device_id: str) -> dict[str, Any]:
    device = get_device(device_id)
    if device is None:
        raise HTTPException(status_code=404, detail="Device not found")
    device["recentJobs"] = [job for job in list_jobs(20) if job["device_id"] == device_id][:10]
    return device


@app.post("/api/devices/{device_id}/commands", dependencies=[Depends(verify_api_token)])
async def api_send_command(device_id: str, payload: dict[str, Any]) -> JSONResponse:
    device = get_device(device_id)
    if device is None or not device["online"]:
        error_payload = build_error_payload("DEVICE_OFFLINE", "Device is offline")
        return JSONResponse(status_code=503, content=error_payload)

    job_id = create_job(
        device_id=device_id,
        kind="manual_command",
        status_value="running",
        command_name=str(payload.get("command", "unknown")),
    )

    try:
        request_id, status_code, response = await dispatch_command(device_id, payload)
        sanitized, artifact_count = strip_artifacts(response, job_id=job_id, step_id=None)
        update_job(
            job_id,
            status_value="succeeded" if response.get("ok") else "failed",
            request_id=request_id,
            response=sanitized,
            error_code=response.get("errorCode"),
            error_message=response.get("error"),
            artifact_count=artifact_count,
            completed=True,
        )
        return JSONResponse(status_code=status_code, content=response)
    except HTTPException as exc:
        error_code = "DEVICE_BUSY" if exc.status_code == 409 else "DEVICE_OFFLINE"
        payload = build_error_payload(error_code, str(exc.detail))
        update_job(
            job_id,
            status_value="failed",
            response=payload,
            error_code=error_code,
            error_message=str(exc.detail),
            completed=True,
        )
        return JSONResponse(status_code=exc.status_code, content=payload)


@app.get("/api/workflows", dependencies=[Depends(verify_api_token)])
async def api_list_workflows() -> list[dict[str, Any]]:
    return list_workflows()


@app.post("/api/workflows", dependencies=[Depends(verify_api_token)])
async def api_create_workflow(workflow: WorkflowModel) -> dict[str, Any]:
    workflow_id = save_workflow(workflow)
    return {"ok": True, "workflowId": workflow_id, "workflow": get_workflow(workflow_id)}


@app.put("/api/workflows/{workflow_id}", dependencies=[Depends(verify_api_token)])
async def api_update_workflow(workflow_id: str, workflow: WorkflowModel) -> dict[str, Any]:
    if get_workflow(workflow_id) is None:
        raise HTTPException(status_code=404, detail="Workflow not found")
    save_workflow(workflow, workflow_id)
    return {"ok": True, "workflowId": workflow_id, "workflow": get_workflow(workflow_id)}


@app.post("/api/devices/{device_id}/workflow-runs", dependencies=[Depends(verify_api_token)])
async def api_start_workflow_run(device_id: str, request: WorkflowRunModel) -> dict[str, Any]:
    device = get_device(device_id)
    if device is None or not device["online"]:
        raise HTTPException(status_code=503, detail="Device is offline")
    workflow = get_workflow(request.workflowId)
    if workflow is None:
        raise HTTPException(status_code=404, detail="Workflow not found")
    if device["busy"]:
        raise HTTPException(status_code=409, detail="Device is busy")

    run_id = create_job(
        device_id=device_id,
        kind="workflow_run",
        status_value="queued",
        workflow_template_id=request.workflowId,
        command_name="workflow",
    )
    task = asyncio.create_task(run_workflow_job(run_id, device_id, workflow))
    workflow_tasks[run_id] = task
    return {"ok": True, "runId": run_id, "workflowId": request.workflowId, "deviceId": device_id}


@app.get("/api/workflow-runs/{run_id}", dependencies=[Depends(verify_api_token)])
async def api_get_workflow_run(run_id: str) -> dict[str, Any]:
    job = get_job(run_id)
    if job is None:
        raise HTTPException(status_code=404, detail="Workflow run not found")
    return job


@app.get("/api/jobs", dependencies=[Depends(verify_api_token)])
async def api_list_jobs(limit: int = 20) -> list[dict[str, Any]]:
    return list_jobs(limit=limit)


@app.websocket("/ws/client")
async def ws_client(websocket: WebSocket) -> None:
    token = websocket.query_params.get("token")
    await websocket.accept()
    if token != SHARED_TOKEN:
        await websocket.close(code=4401, reason="Invalid token")
        return

    device_id = None
    connection: DeviceConnection | None = None
    try:
        hello_text = await websocket.receive_text()
        hello_message = json.loads(hello_text)
        if hello_message.get("type") != "client.hello":
            await websocket.close(code=4400, reason="Expected client.hello")
            return

        device_id = hello_message.get("deviceId") or hello_message.get("hello", {}).get("deviceId")
        if not device_id:
            await websocket.close(code=4400, reason="Missing deviceId")
            return

        if device_id in connections:
            try:
                await connections[device_id].websocket.close(code=4000, reason="Superseded by new connection")
            except Exception:
                pass
        connection = DeviceConnection(device_id=device_id, websocket=websocket)
        connections[device_id] = connection
        set_device_online(device_id, hello_message.get("hello", {}))
        await websocket.send_text(json.dumps({"type": "server.hello.ack", "deviceId": device_id}))

        while True:
            text = await websocket.receive_text()
            message = json.loads(text)
            message_type = message.get("type")
            if message_type == "client.command.result":
                request_id = message.get("requestId")
                response = message.get("response") or {}
                status_code = int(message.get("statusCode", 200))
                future = connection.pending_results.get(request_id)
                if future is not None and not future.done():
                    future.set_result({"statusCode": status_code, "response": response})
            elif message_type == "client.status.update":
                status_payload = message.get("status") or {}
                update_device_status(device_id, status_payload, status_payload.get("lastError"))
    except WebSocketDisconnect as exc:
        if device_id:
            mark_device_offline(device_id, f"Disconnected: {exc.code}")
    except Exception as exc:
        if device_id:
            mark_device_offline(device_id, str(exc))
    finally:
        if connection is not None:
            for future in connection.pending_results.values():
                if not future.done():
                    future.set_exception(RuntimeError("Device disconnected"))
            if connections.get(connection.device_id) is connection:
                connections.pop(connection.device_id, None)


def run() -> None:
    import uvicorn

    uvicorn.run("server.app:app", host="0.0.0.0", port=int(os.environ.get("PORT", "8080")), reload=False)
