# -*- coding: utf-8 -*-
"""构建 OfferPilot S4 评测集（25 组合成 JD-简历配对样本）。

设计原则：
1. 全部为合成样本（synthetic=true）：姓名/公司/联系方式均为虚构占位，不含任何真实个人信息。
2. 标注独立性：标注分由**规则化独立评审流程**产生——对每组样本先给出五个维度的 1-5 整数分，
   再按固定权重（硬性技能 30 / 经验年限 20 / 项目成果 25 / 教育证书 10 / 加分项 15）
   加权后四舍五入为 1-5 整数总分。全程不调用模型、不读取任何模型输出。
3. 人工复核位：label.json 中保留 human_review 字段（status=PENDING），供后续人工抽检回填。
4. 档位分布：高度匹配 6 / 较匹配 9 / 一般 7 / 不匹配 3，合计 25，符合需求说明书第 8 章。

产出目录结构：
    eval/dataset/<case_id>/jd.txt
    eval/dataset/<case_id>/resume.txt
    eval/dataset/<case_id>/label.json
    eval/dataset/_index.csv        （清单，含档位/标注分/合成标识/复核状态）
    eval/dataset/_dataset_meta.json（评测集元信息与档位分布统计）
"""

import csv
import json
import os
import sys
from datetime import date
from decimal import Decimal, ROUND_HALF_UP

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cases_part1 import CASES_PART1  # noqa: E402
from cases_part2 import CASES_PART2  # noqa: E402

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATASET_DIR = os.path.join(BASE_DIR, "dataset")
LABEL_DATE = date.today().isoformat()

DIM_WEIGHTS = {
    "硬性技能匹配": 30,
    "经验年限与职级": 20,
    "项目与成果相关度": 25,
    "教育与证书": 10,
    "加分项与潜力": 15,
}

TIER_EXPECT = {
    "高度匹配": [5],
    "较匹配": [4],
    "一般": [3],
    "不匹配": [1, 2],
}

CASES = CASES_PART1 + CASES_PART2


def weighted_score(dims):
    total = Decimal(0)
    for k, w in DIM_WEIGHTS.items():
        total += Decimal(dims[k]) * Decimal(w)
    return total / Decimal(100)


def to_int_score(x):
    return int(Decimal(x).quantize(Decimal("1"), rounding=ROUND_HALF_UP))


def main():
    os.makedirs(DATASET_DIR, exist_ok=True)
    rows = []
    tier_counter = {}

    for case in CASES:
        cid = case["case_id"]
        cdir = os.path.join(DATASET_DIR, cid)
        os.makedirs(cdir, exist_ok=True)

        dims = case["dims"]
        assert list(dims.keys()) == list(DIM_WEIGHTS.keys()), f"{cid} 维度键不匹配"
        for k, v in dims.items():
            assert isinstance(v, int) and 1 <= v <= 5, f"{cid} 维度分越界: {k}={v}"

        w = weighted_score(dims)
        score = to_int_score(w)
        expect = TIER_EXPECT[case["tier"]]
        assert score in expect, f"{cid} 档位 {case['tier']} 与标注分 {score}（加权 {w}）不一致"

        with open(os.path.join(cdir, "jd.txt"), "w", encoding="utf-8") as f:
            f.write(case["jd"].strip() + "\n")
        with open(os.path.join(cdir, "resume.txt"), "w", encoding="utf-8") as f:
            f.write(case["resume"].strip() + "\n")

        label = {
            "case_id": cid,
            "synthetic": True,
            "sample_type": "合成样本（人物、公司、联系方式均为虚构占位，与任何真实自然人或企业无关）",
            "job_family": case["job_family"],
            "tier": case["tier"],
            "human_score": score,
            "human_dimensions": dims,
            "score_rule": "维度分按 硬性技能30/经验年限20/项目成果25/教育证书10/加分项15 加权后四舍五入为 1-5 整数",
            "weighted_raw": float(w),
            "label_method": "规则化独立评审流程（确定性打分规则推导，标注阶段未运行也未查看任何模型输出）",
            "label_date": LABEL_DATE,
            "note": case["note"],
            "human_review": {
                "status": "PENDING",
                "reviewer": None,
                "reviewed_at": None,
                "review_note": None,
            },
        }
        with open(os.path.join(cdir, "label.json"), "w", encoding="utf-8") as f:
            json.dump(label, f, ensure_ascii=False, indent=2)

        tier_counter[case["tier"]] = tier_counter.get(case["tier"], 0) + 1
        rows.append({
            "case_id": cid,
            "tier": case["tier"],
            "job_family": case["job_family"],
            "human_score": score,
            "weighted_raw": round(float(w), 3),
            "d_硬性技能": dims["硬性技能匹配"],
            "d_经验年限": dims["经验年限与职级"],
            "d_项目成果": dims["项目与成果相关度"],
            "d_教育证书": dims["教育与证书"],
            "d_加分项": dims["加分项与潜力"],
            "synthetic": "Y",
            "review_status": "PENDING",
        })

    with open(os.path.join(DATASET_DIR, "_index.csv"), "w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)

    meta = {
        "dataset_name": "OfferPilot S4 评测集（合成样本）",
        "built_at": LABEL_DATE,
        "total_cases": len(CASES),
        "synthetic": True,
        "disclaimer": "全部样本为合成数据，仅用于系统评测；不得用于任何真实招聘或人事决策。",
        "tier_distribution": {
            "高度匹配(模型分≥80)": tier_counter.get("高度匹配", 0),
            "较匹配(60-79)": tier_counter.get("较匹配", 0),
            "一般(40-59)": tier_counter.get("一般", 0),
            "不匹配(<40)": tier_counter.get("不匹配", 0),
        },
        "human_score_distribution": {str(s): sum(1 for r in rows if r["human_score"] == s) for s in sorted({r["human_score"] for r in rows})},
        "file_layout": "dataset/<case_id>/{jd.txt, resume.txt, label.json}",
        "note_on_resume_format": "合成样本以纯文本 resume.txt 提供，与后端 /api/match 的文本入参（jdText/resumeText）对齐；离线真实样本可另存为 resume.pdf。",
    }
    with open(os.path.join(DATASET_DIR, "_dataset_meta.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)

    print(json.dumps({"ok": True, "dataset_dir": DATASET_DIR, "total": len(CASES),
                      "tier_distribution": tier_counter}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
