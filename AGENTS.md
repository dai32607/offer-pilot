# OfferPilot 项目上下文（AGENTS.md）

## 项目定位
JD-简历匹配分析平台（求职作品集项目，AI 应用/Agent 开发方向）。功能：JD 与简历匹配诊断、改写建议、模拟面试。技术栈 Spring Boot 3.2.12 + Java 21 + Vue3.4 + Element Plus + Vite + DeepSeek API（REST+SSE），无数据库。

## 目录结构
- backend/：Spring Boot 服务（REST+SSE），文件持久化 data/session，无数据库
- frontend/：Vue3 + Vite，dev 代理 5173 → 8083
- eval/：评测脚本与数据集 C01-C25（合成样本 synthetic=true）
- samples/：演示样例（jd-sample.md / resume-sample.md）
- docs/：设计文档、演示脚本 demo-script.md
- output/：报告与截图产物
- tools/：演示包工具（demo-package 启动/停止脚本、打包演示包.bat 在根目录）

## 构建与启动
- 后端（jar，端口 8083）：C:\Program Files\Java\jdk-21.0.10\bin\java -jar backend\target\offerpilot-backend.jar
- 后端重新打包：用 JDK21 全路径执行 mvn package（系统默认 PATH 是 JDK8，必须显式用 JDK21）
- 前端 dev（5173）：cd frontend && npm install && npm run dev
- 前端打包：cd frontend && npm run build（产物 dist/，mvn package 时并入 jar static）
- 演示包：桌面 OfferPilot-Demo\启动演示.bat（端口自动选 8083/8085/8087）；根目录 打包演示包.bat 用于重新打包同步演示文件夹与 zip

## 关键模块
- MatchService：匹配计分（硬性技能锚定，81/47/20 权重）
- DemoCacheService / DemoSnapshotService：时延治理与离线回放（缓存命中 P50 37ms，离线回放 P95 13.4s）
- InterviewService / InterviewAgent / InterviewSessionStore：模拟面试会话状态机（文件持久化 data/session，60s 超时回滚）

## 演示口径红线（重要，不可违反）
- 评测数据全部为合成样本（synthetic=true），标签按分数区间对齐，同档率/准确率类指标仅内部参考，对外严禁称"准确率"
- 现场演示统一讲 C01 匹配页 88.1 分，不并提其他分数
- 演示包为离线快照回放（offline_replay=true / fallback-to-real=false），报告分数是快照回放值
- 外部凭据不硬编码、不写库：DEEPSEEK_API_KEY 用 ${DEEPSEEK_API_KEY:} 环境变量引用

## 编码约定
- 注释与回复用中文；后端接口返回结构 code/message/data；任何 Prompt/参数改动必须全量重跑评测对比 ρ/MAE/同档率，劣化即回滚
*（内容由AI生成，仅供参考）*
