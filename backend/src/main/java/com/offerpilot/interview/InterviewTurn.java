package com.offerpilot.interview;

import java.util.ArrayList;
import java.util.List;

/**
 * 面试会话中的一轮问答记录（对应 interview_session.transcript_json 的元素）：
 * {round, question, answer, evaluation} + 本轮考察维度与证据引用（3.6 / 7.7）。
 */
public class InterviewTurn {

    private int round;
    private String question;
    private String focusDimension;
    private String evidenceRef;
    private List<String> answerPoints = new ArrayList<>();
    private String followupHint;
    private String answer;
    private Double score;
    private List<String> strengths = new ArrayList<>();
    private List<String> improvements = new ArrayList<>();
    private String answeredAt;
    private long latencyMs;

    public InterviewTurn() {
    }

    public InterviewTurn(int round) {
        this.round = round;
    }

    public int getRound() {
        return round;
    }

    public void setRound(int round) {
        this.round = round;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getFocusDimension() {
        return focusDimension;
    }

    public void setFocusDimension(String focusDimension) {
        this.focusDimension = focusDimension;
    }

    public String getEvidenceRef() {
        return evidenceRef;
    }

    public void setEvidenceRef(String evidenceRef) {
        this.evidenceRef = evidenceRef;
    }

    public List<String> getAnswerPoints() {
        return answerPoints;
    }

    public void setAnswerPoints(List<String> answerPoints) {
        this.answerPoints = answerPoints == null ? new ArrayList<>() : new ArrayList<>(answerPoints);
    }

    public String getFollowupHint() {
        return followupHint;
    }

    public void setFollowupHint(String followupHint) {
        this.followupHint = followupHint;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public List<String> getStrengths() {
        return strengths;
    }

    public void setStrengths(List<String> strengths) {
        this.strengths = strengths == null ? new ArrayList<>() : new ArrayList<>(strengths);
    }

    public List<String> getImprovements() {
        return improvements;
    }

    public void setImprovements(List<String> improvements) {
        this.improvements = improvements == null ? new ArrayList<>() : new ArrayList<>(improvements);
    }

    public String getAnsweredAt() {
        return answeredAt;
    }

    public void setAnsweredAt(String answeredAt) {
        this.answeredAt = answeredAt;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    /** 是否已回答（未回答的轮次不出现在评分汇总中）。 */
    public boolean answered() {
        return answer != null && !answer.isBlank();
    }
}
