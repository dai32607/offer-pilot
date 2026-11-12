#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
OfferPilot S6 后端接口冒烟脚本（最小可复跑）
================================================
流程：确保后端就绪(8083) -> 创建分析任务 -> 等待任务完成 -> 开启面试
      -> 回答 1 轮 -> 校验会话状态 -> 输出 PASS/FAIL 摘要。

依赖：仅 Python 标准库（urllib），无第三方包。
复跑：任意时刻再次执行即可。若目标端口已有后端在监听则直接复用，
      否则按 --java/--args-file 自动拉起（后台进程，日志落 backend/live-smoke-8083.log）。

用法示例：
    python eval/scripts/smoke_s6_interview.py
    python eval/scripts/smoke_s6_interview.py --port 8083 --task-wait 300
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # offer-pilot 根
DEFAULT_PORT = 8083
DEFAULT_JAVA = r"C:\Program Files\Java\jdk-21.0.10\bin\java.exe"
DEFAULT_ARGS = os.path.join(BASE_DIR, "backend", "target", "offerpilot-backend.jar")

JD_TEXT = (
    "岗位：Java 后端工程师。要求：3 年以上 Java 与 Spring Boot 开发经验，"
    "熟悉 MySQL、Redis、消息队列，有高并发系统设计与性能优化经验，"
    "具备良好的代码规范与团队协作能力，本科及以上学历。"
)
RESUME_TEXT = (
    "张三，5 年 Java 开发经验，熟练 Spring Boot、MySQL、Redis、Kafka，"
    "主导过日均百万请求的订单系统重构，性能提升 40%，本科计算机专业。"
)
ANSWER_TEXT = (
    "我有 5 年 Java 后端经验，使用 Spring Boot 完成过订单、支付、库存等核心服务，"
    "熟悉 MySQL 索引优化与 Redis 缓存，主导过高并发系统重构，性能提升明显，"
    "能与团队高效协作并坚持代码规范。"
)

KEY_ALIASES = {
    "session_id": ["session_id", "sessionId", "id"],
    "round_no": ["round_no", "round", "roundNo"],
    "status": ["status"],
    "answered": ["answered", "answeredFlag"],
    "score": ["score", "scoreTotal"],
}


def pick(data, key):
    """多别名容错取值。"""
    if not isinstance(data, dict):
        return None
    for k in KEY_ALIASES.get(key, [key]):
        if k in data:
            return data[k]
    return None


def http_json(url, method="GET", body=None, timeout=180):
    req = urllib.request.Request(url, method=method)
    req.add_header("Content-Type", "application/json")
    data = json.dumps(body).encode("utf-8") if body is not None else None
    with urllib.request.urlopen(req, data=data, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8")
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {"code": -1, "message": "non-json: " + raw[:200], "data": None}


def api(base, path, method="GET", body=None, timeout=180, ok_expected=True):
    url = base + path
    resp = http_json(url, method, body, timeout)
    if ok_expected and (not isinstance(resp, dict) or resp.get("code") != 0):
        raise RuntimeError(f"{method} {path} -> code={resp.get('code')} message={resp.get('message')} body={str(resp)[:300]}")
    return resp.get("data") if isinstance(resp, dict) else None


def wait_backend(base, timeout=90):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            api(base, "/api/v1/tasks", timeout=5)
            return True
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(1)
    raise RuntimeError(f"后端 {base} 在 {timeout}s 内未就绪，最后错误: {last}")


def ensure_backend(port, java, args_file, jar, backend_dir, log_dir):
    base = f"http://127.0.0.1:{port}"
    try:
        api(base, "/api/v1/tasks", timeout=3)
        print(f"[backend] 端口 {port} 已有后端在监听，直接复用。")
        return base, None
    except Exception:  # noqa: BLE001
        pass

    cmd = []
    if args_file and os.path.exists(args_file):
        cmd = [java, "@" + args_file]
    elif os.path.exists(jar):
        cmd = [java, "-jar", jar, f"--server.port={port}"]
    else:
        raise RuntimeError("未找到后端可执行体：--args-file 与 --jar 均不可用")

    log_path = os.path.join(log_dir, f"live-smoke-{port}.log")
    proc = subprocess.Popen(cmd, cwd=backend_dir, stdout=open(log_path, "w", encoding="utf-8"),
                            stderr=subprocess.STDOUT)
    print(f"[backend] 已拉起后端 PID={proc.pid}，日志={log_path}")
    wait_backend(base)
    return base, proc


def wait_task(base, task_no, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        data = api(base, f"/api/v1/tasks/{task_no}")
        status = pick(data, "status")
        if status == "SUCCEEDED":
            return data
        if status == "FAILED":
            raise RuntimeError(f"任务 {task_no} 失败: {data}")
        time.sleep(2)
    raise RuntimeError(f"任务 {task_no} 在 {timeout}s 内未完成")


def main():
    ap = argparse.ArgumentParser(description="OfferPilot S6 接口冒烟")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    ap.add_argument("--java", default=DEFAULT_JAVA)
    ap.add_argument("--args-file", default="", help="java @argsfile（含 -cp 与主类），优先于 --jar")
    ap.add_argument("--jar", default=DEFAULT_ARGS)
    ap.add_argument("--task-wait", type=int, default=300, help="等待任务完成的最长秒数")
    ap.add_argument("--answer-wait", type=int, default=240, help="等待单轮回答评分的最大秒数")
    args = ap.parse_args()

    backend_dir = os.path.join(BASE_DIR, "backend")
    log_dir = os.path.join(BASE_DIR, "backend")
    base, proc = ensure_backend(args.port, args.java, args.args_file, args.jar, backend_dir, log_dir)

    results = {}
    start_all = time.time()
    try:
        # 1) 创建任务
        task_no = "SMOKE-S6-" + datetime.now().strftime("%Y%m%d%H%M%S")
        data = api(base, "/api/v1/tasks", "POST",
                   {"taskNo": task_no, "jdText": JD_TEXT, "resumeText": RESUME_TEXT})
        results["task_no"] = task_no
        results["task_create_status"] = pick(data, "status")
        print(f"[task] 创建 {task_no} -> status={results['task_create_status']}")

        # 2) 等待任务完成
        task_view = wait_task(base, task_no, args.task_wait)
        results["task_status"] = pick(task_view, "status")
        results["task_progress"] = pick(task_view, "progress")
        results["report_ready"] = pick(task_view, "reportReady")
        print(f"[task] 完成 status={results['task_status']} progress={results['task_progress']} reportReady={results['report_ready']}")

        # 3) 开启面试（最多 1 轮；契约字段为 snake_case: max_round）
        data = api(base, f"/api/v1/tasks/{task_no}/interviews", "POST", {"max_round": 1})
        session_id = pick(data, "session_id")
        question = data.get("question")
        results["session_id"] = session_id
        results["question_present"] = bool(question)
        print(f"[interview] 开启 session={session_id} question_present={bool(question)}")

        # 4) 回答 1 轮（同步等待评分）
        data = api(base, f"/api/v1/interviews/{session_id}/messages", "POST",
                   {"answer": ANSWER_TEXT}, timeout=args.answer_wait)
        results["round_no"] = pick(data, "round_no")
        results["answered_evaluation_present"] = data.get("evaluation") is not None
        results["next_question"] = data.get("question")
        print(f"[interview] 答完 round={results['round_no']} "
              f"evaluation_present={results['answered_evaluation_present']} next_question={results['next_question']}")

        # 5) 验状态（detail 视图：status/score 对象/transcript）
        detail = api(base, f"/api/v1/interviews/{session_id}")
        results["detail_status"] = pick(detail, "status")
        results["detail_round_no"] = pick(detail, "round_no")
        score_obj = pick(detail, "score")
        results["score_avg"] = score_obj.get("avg_score") if isinstance(score_obj, dict) else None
        results["score_answered_rounds"] = score_obj.get("answered_rounds") if isinstance(score_obj, dict) else None
        transcript = detail.get("transcript") if isinstance(detail, dict) else None
        results["transcript_present"] = bool(transcript)
        print(f"[interview] detail status={results['detail_status']} round={results['detail_round_no']} "
              f"score_avg={results['score_avg']} answered_rounds={results['score_answered_rounds']} transcript_present={results['transcript_present']}")

        # 6) 汇总判定
        ok = (
            results.get("task_status") == "SUCCEEDED"
            and results.get("report_ready") is True
            and results.get("session_id") is not None
            and results.get("question_present") is True
            and results.get("round_no") == 1
            and results.get("answered_evaluation_present") is True
            and results.get("next_question") is None
            and results.get("detail_status") == "FINISHED"
            and results.get("score_avg") is not None
            and results.get("score_answered_rounds") == 1
            and results.get("transcript_present") is True
        )
        results["PASS"] = bool(ok)
        results["elapsed_sec"] = round(time.time() - start_all, 1)
        print("=" * 60)
        print(f"RESULT: {'PASS' if ok else 'FAIL'} (elapsed={results['elapsed_sec']}s)")
        print(json.dumps(results, ensure_ascii=False, indent=2))
        return 0 if ok else 1
    finally:
        if proc is not None:
            # 脚本自动拉起的后端保持运行（供前端联调），不在此处 kill
            print(f"[backend] 由本脚本拉起的后端仍在运行 PID={proc.pid}（未主动停止）")


if __name__ == "__main__":
    sys.exit(main())
