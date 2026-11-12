# -*- coding: utf-8 -*-
"""OfferPilot S4 评测跑批脚本：批量调用后端 POST /api/match，逐组记录结果。

特性：
- 增量落盘：每组完成后立即追加写入 results.jsonl，中断可续跑（默认跳过已成功组）。
- 支持按 case 范围分批执行（评测集单组耗时约 1-3 分钟，分批跑便于控制单次窗口）。
- 记录内容：模型 overallScore、后端 level、客户端实测耗时、各阶段耗时与 token、维度分、
  不确定维度与重归一化标记、失败原因。

用法：
    python run_eval.py --base-url http://127.0.0.1:8082 --cases C01,C02,C03
    python run_eval.py --start 4 --end 25
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATASET_DIR = os.path.join(BASE_DIR, "dataset")
RESULT_DIR = os.path.join(BASE_DIR, "results")
JSONL_PATH = os.path.join(RESULT_DIR, "results.jsonl")


def load_cases():
    cases = []
    for name in sorted(os.listdir(DATASET_DIR)):
        cdir = os.path.join(DATASET_DIR, name)
        if not os.path.isdir(cdir) or not name.startswith("C"):
            continue
        jd_path = os.path.join(cdir, "jd.txt")
        resume_path = os.path.join(cdir, "resume.txt")
        label_path = os.path.join(cdir, "label.json")
        if not (os.path.exists(jd_path) and os.path.exists(resume_path) and os.path.exists(label_path)):
            continue
        with open(jd_path, encoding="utf-8") as f:
            jd = f.read()
        with open(resume_path, encoding="utf-8") as f:
            resume = f.read()
        with open(label_path, encoding="utf-8") as f:
            label = json.load(f)
        cases.append({"case_id": name, "jd": jd, "resume": resume, "label": label})
    return cases


def load_done_ids(jsonl_path=None):
    jsonl_path = jsonl_path or JSONL_PATH
    done = set()
    if os.path.exists(jsonl_path):
        with open(jsonl_path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if obj.get("ok"):
                    done.add(obj["case_id"])
    return done


def call_match(base_url, jd, resume, seed, timeout):
    payload_dict = {"jdText": jd, "resumeText": resume}
    if seed is not None:
        payload_dict["seed"] = seed
    payload = json.dumps(payload_dict).encode("utf-8")
    req = urllib.request.Request(
        base_url.rstrip("/") + "/api/match",
        data=payload,
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def run_one(base_url, case, seed, timeout):
    t0 = time.time()
    record = {
        "case_id": case["case_id"],
        "tier": case["label"]["tier"],
        "job_family": case["label"]["job_family"],
        "human_score": case["label"]["human_score"],
        "human_dimensions": case["label"]["human_dimensions"],
        "run_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "ok": False,
    }
    try:
        body = call_match(base_url, case["jd"], case["resume"], seed, timeout)
    except urllib.error.HTTPError as e:
        record["elapsedMs"] = int((time.time() - t0) * 1000)
        record["error"] = f"HTTP {e.code}: {e.read().decode('utf-8', 'ignore')[:500]}"
        return record
    except Exception as e:  # 网络/超时等
        record["elapsedMs"] = int((time.time() - t0) * 1000)
        record["error"] = f"{type(e).__name__}: {e}"
        return record

    record["elapsedMs"] = int((time.time() - t0) * 1000)
    if body.get("code") != 0 or not body.get("data"):
        record["error"] = f"api code={body.get('code')} message={body.get('message')}"
        return record

    data = body["data"]
    report = data["report"]
    meta = data["meta"]
    record.update({
        "ok": True,
        "overallScore": report["overallScore"],
        "level": report["level"],
        "dimensions": [
            {
                "name": d["name"],
                "weight": d["weight"],
                "score": d["score"],
                "maxScore": d["maxScore"],
                "uncertainty": d.get("uncertainty", False),
            }
            for d in report.get("dimensions", [])
        ],
        "hits_count": len(report.get("hits", []) or []),
        "gaps_count": len(report.get("gaps", []) or []),
        "risks_count": len(report.get("risks", []) or []),
        "invalidDimensions": meta.get("invalidDimensions", []),
        "renormalized": meta.get("renormalized", False),
        "uncertaintyCount": meta.get("uncertaintyCount", 0),
        "dimensionCount": meta.get("dimensionCount", 0),
        "notes": report.get("notes", []),
        "matchModel": meta.get("matchModel"),
        "stages": {
            "jdParse": meta.get("jdParse"),
            "resumeParse": meta.get("resumeParse"),
            "matchEval": meta.get("matchEval"),
        },
    })
    return record


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://127.0.0.1:8082")
    ap.add_argument("--cases", default="")
    ap.add_argument("--start", type=int, default=1)
    ap.add_argument("--end", type=int, default=999)
    ap.add_argument("--timeout", type=float, default=420.0)
    ap.add_argument("--seed", type=int, default=None, help="固定随机种子，透传给后端 /api/match 的 seed 字段")
    ap.add_argument("--no-skip-done", action="store_true")
    ap.add_argument("--out", default="", help="结果 jsonl 路径，默认 eval/results/results.jsonl")
    args = ap.parse_args()

    out_path = args.out or JSONL_PATH
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    all_cases = load_cases()
    if args.cases:
        wanted = {c.strip().upper() for c in args.cases.split(",") if c.strip()}
        selected = [c for c in all_cases if c["case_id"].upper() in wanted]
    else:
        selected = [c for c in all_cases if args.start <= int(c["case_id"][1:]) <= args.end]

    done = set() if args.no_skip_done else load_done_ids(out_path)
    todo = [c for c in selected if c["case_id"] not in done]

    print(f"[eval] base_url={args.base_url} selected={len(selected)} done={len(done)} todo={len(todo)}", flush=True)
    if not todo:
        print("[eval] nothing to do", flush=True)
        return

    ok_cnt, fail_cnt = 0, 0
    for i, case in enumerate(todo, 1):
        rec = run_one(args.base_url, case, args.seed, args.timeout)
        with open(out_path, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        if rec["ok"]:
            ok_cnt += 1
            print(f"[{i}/{len(todo)}] {rec['case_id']} OK score={rec['overallScore']} level={rec['level']} "
                  f"elapsed={rec['elapsedMs']}ms tokens={rec['stages']['matchEval']['totalTokens'] if rec['stages'].get('matchEval') else 'NA'}", flush=True)
        else:
            fail_cnt += 1
            print(f"[{i}/{len(todo)}] {rec['case_id']} FAIL elapsed={rec['elapsedMs']}ms err={rec.get('error')}", flush=True)

    summary = {"batch_done": len(todo), "ok": ok_cnt, "fail": fail_cnt, "jsonl": out_path}
    print("[eval] batch summary: " + json.dumps(summary, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
