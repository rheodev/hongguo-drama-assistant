#!/usr/bin/env python3
"""Two diagnostic snapshots only; no phone settings, polling or battery resets."""

import argparse
import json
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

COMPONENT = "dev.hongguo.dramaassistant/.DramaAccessibilityService"


def snapshot(serial):
    result = subprocess.run(
        ["adb", "-s", serial, "shell", "dumpsys", "activity", "service", COMPONENT],
        check=True, capture_output=True, text=True, timeout=20,
    )
    values = {}
    for line in result.stdout.splitlines():
        key, separator, value = line.strip().partition("=")
        if not separator or not key.isidentifier():
            continue
        if value in ("true", "false"):
            values[key] = value == "true"
        else:
            try:
                values[key] = int(value)
            except ValueError:
                values[key] = value
    if "processCpuMs" not in values:
        raise RuntimeError("未取得诊断数据，请先在手机上开启助手的无障碍服务。")
    return values


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--duration", type=int, default=30)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--expected-state", choices=("paused", "running"))
    args = parser.parse_args()
    if args.duration < 1:
        parser.error("duration must be positive")
    start = snapshot(args.serial)
    if args.expected_state and start["running"] != (args.expected_state == "running"):
        raise RuntimeError("当前运行状态不符合预期，请在手机上确认后再采样。")
    print(f"已开始采样，{args.duration} 秒后读取第二次快照。", flush=True)
    time.sleep(args.duration)
    end = snapshot(args.serial)
    elapsed = end["elapsedRealtimeMs"] - start["elapsedRealtimeMs"]
    if elapsed <= 0 or end["pid"] != start["pid"] or end["processCpuMs"] < start["processCpuMs"]:
        raise RuntimeError("设备或进程在采样期间重启，结果无效。")
    delta = {
        key: end[key] - start[key]
        for key in ("events", "inspections", "gestures", "totalInspectMicros", "processCpuMs")
    }
    if any(value < 0 for value in delta.values()):
        raise RuntimeError("进程在采样期间重启，计数已重置。")
    if end["running"] != start["running"]:
        raise RuntimeError("采样期间运行状态改变，结果无效，请重新采样。")
    report = {
        "recordedAt": datetime.now(timezone.utc).isoformat(),
        "elapsedMs": elapsed,
        "start": start,
        "end": end,
        "delta": delta,
        "singleCoreCpuPercent": round(delta["processCpuMs"] / elapsed * 100, 3),
        "note": "包含诊断开销；短时 CPU 测量不能直接推导耗电。",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
