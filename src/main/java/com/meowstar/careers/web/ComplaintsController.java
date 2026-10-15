package com.meowstar.careers.web;

import com.meowstar.careers.auth.AuthUser;
import com.meowstar.careers.error.ApiException;
import com.meowstar.careers.model.complaint.ComplaintRules;
import com.meowstar.careers.model.complaint.ComplaintView;
import com.meowstar.careers.model.complaint.CreateComplaint;
import com.meowstar.careers.model.complaint.ReleaseLockQuery;
import com.meowstar.careers.model.complaint.ReviewComplaint;
import com.meowstar.careers.model.user.User;
import com.meowstar.careers.repo.ComplaintRepo;
import com.meowstar.careers.repo.UserRepo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 投诉端点：发起（求职者，需实际沟通+有效证据）/ 查询 / 认领锁 / 审核。
 *
 * <p>发起: POST /api/v1/companies/{id}/complaints {evidence}
 * <br>查询: GET /api/v1/complaints/mine（求职者我的投诉）、
 * GET /api/v1/complaints（审核账号/管理员=全部，招聘者=本企业，求职者=本人；?status=）
 * <br>认领: POST /api/v1/complaints/{id}/claim —— 认领即锁定（重复调用=续约）
 * <br>释放: POST /api/v1/complaints/{id}/release?force= —— 本人释放；管理员可强制释放
 * <br>审核: POST /api/v1/complaints/{id}/review {approved, note?}
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "complaint", description = "投诉（举报）与审核（审核账号 / 平台管理员）")
public class ComplaintsController {

    /** 投诉审核状态取值（与 0012_complaints.sql 的 CHECK 约束一致）。 */
    private static final List<String> COMPLAINT_STATUSES = List.of("pending", "approved", "rejected");

    private final ComplaintRepo complaintRepo;
    private final UserRepo userRepo;

    public ComplaintsController(ComplaintRepo complaintRepo, UserRepo userRepo) {
        this.complaintRepo = complaintRepo;
        this.userRepo = userRepo;
    }

    /** 发起投诉（仅求职者；要求与该用人单位有过实际沟通：存在会话且至少一条消息）。 */
    @Operation(summary = "发起投诉", description = "仅求职者；须与该企业有过实际沟通（会话内至少一条消息），证据 20-5000 字符")
    @PostMapping("/companies/{companyId}/complaints")
    public ResponseEntity<ComplaintView> createComplaint(
            AuthUser auth,
            @PathVariable UUID companyId,
            @RequestBody CreateComplaint payload) {
        String evidence = payload == null ? null : payload.evidence();
        validateEvidence(evidence);

        User me = userRepo.getOrNotFound(auth.userId());
        if (!"seeker".equals(me.role())) {
            throw ApiException.forbidden("仅求职者可发起投诉");
        }
        if (!complaintRepo.companyExists(companyId)) {
            throw ApiException.notFoundResource("company `" + companyId + "`");
        }
        // 规则：投诉前必须与该用人单位有过实际交流（存在会话且至少一条消息）
        if (!complaintRepo.hasExchange(auth.userId(), companyId)) {
            throw ApiException.forbidden("投诉前需与该用人单位有过实际沟通（如已发起会话并发送过消息）");
        }

        ComplaintView view = complaintRepo.create(companyId, auth.userId(), evidence.trim());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /** 我的投诉（求职者）。 */
    @Operation(summary = "我的投诉", description = "当前用户发起的投诉（按创建时间倒序）")
    @GetMapping("/complaints/mine")
    public List<ComplaintView> myComplaints(AuthUser auth) {
        return complaintRepo.mine(auth.userId());
    }

    /** 角色化列表：审核账号/管理员=全部（可 ?status= 过滤）；招聘者=本企业投诉；求职者=本人。 */
    @Operation(summary = "投诉列表", description = "审核账号/平台管理员=全部（可 ?status= 过滤）；招聘者=本企业；求职者=本人")
    @GetMapping("/complaints")
    public List<ComplaintView> listComplaints(
            AuthUser auth,
            @RequestParam(name = "status", required = false) String status) {
        if (status != null && !COMPLAINT_STATUSES.contains(status)) {
            throw ApiException.badRequest("status 取值: " + String.join(" / ", COMPLAINT_STATUSES));
        }
        User me = userRepo.getOrNotFound(auth.userId());
        return switch (me.role() == null ? "" : me.role()) {
            // 审核账号与平台管理员都能看到全部投诉（?status= 过滤待审队列）
            case "admin", "reviewer" -> complaintRepo.all(status);
            case "recruiter" -> {
                UUID companyId = me.companyId();
                if (companyId == null) {
                    throw ApiException.forbidden("招聘者未绑定企业");
                }
                yield complaintRepo.forCompany(companyId);
            }
            default -> complaintRepo.mine(auth.userId());
        };
    }

    /**
     * 认领投诉（认领即锁定；重复调用即续约）。
     *
     * <p>多审核并行场景下，同一条投诉同一时刻只允许一个审核持有锁；
     * 锁到期（默认 10 分钟）自动失效，可被他人重新认领。
     */
    @Operation(summary = "认领投诉",
            description = "认领即锁定，避免多个审核同时审同一条；重复调用视为续约（延长锁到期时间）")
    @PostMapping("/complaints/{id}/claim")
    public ComplaintView claimComplaint(AuthUser auth, @PathVariable UUID id) {
        User reviewer = userRepo.requireReviewer(auth.userId());
        return complaintRepo.claim(id, reviewer.id(), ComplaintRepo.LOCK_TTL_SECONDS);
    }

    /** 释放认领锁（本人释放；平台管理员可 ?force=true 强制释放他人）。 */
    @Operation(summary = "释放认领锁",
            description = "释放自己认领的投诉；平台管理员可用 force=true 强制解锁。已过期的锁任何人可清理")
    @PostMapping("/complaints/{id}/release")
    public ComplaintView releaseComplaint(
            AuthUser auth,
            @PathVariable UUID id,
            @RequestParam(name = "force", required = false) Boolean force) {
        User actor = userRepo.requireReviewer(auth.userId());
        boolean forced = new ReleaseLockQuery(force).forceOrDefault();
        if (forced && !"admin".equals(actor.role())) {
            throw ApiException.forbidden("仅平台管理员可强制释放他人的认领锁");
        }
        return complaintRepo.release(id, actor.id(), forced);
    }

    /**
     * 审核：通过后累计企业投诉次数（立即影响职位列表排序）；驳回不累计。
     *
     * <p>并行保护：投诉被他人有效锁定时拒绝审结，重复提交由条件更新兜底为 409。
     */
    @Operation(summary = "审核投诉",
            description = "审核账号/平台管理员。被他人在审（持锁）时返回 409；重复审核 409")
    @PostMapping("/complaints/{id}/review")
    public ComplaintView reviewComplaint(
            AuthUser auth,
            @PathVariable UUID id,
            @RequestBody ReviewComplaint payload) {
        User reviewer = userRepo.requireReviewer(auth.userId());
        boolean approved = payload != null && payload.approved();
        String rawNote = payload == null ? null : payload.note();
        // Rust: p.note.as_deref().is_some_and(|note| note.trim().chars().count() > 500)
        if (rawNote != null) {
            String trimmed = rawNote.trim();
            if (trimmed.codePointCount(0, trimmed.length()) > 500) {
                throw ApiException.badRequest("审核备注不能超过 500 字符");
            }
        }
        String note = rawNote == null ? null : rawNote.trim();
        if (note != null && note.isEmpty()) {
            note = null;
        }
        Optional<ComplaintView> view = complaintRepo.review(id, reviewer.id(), approved, note);
        return view.orElseThrow(() -> ApiException.notFoundResource("complaint `" + id + "`"));
    }

    /**
     * 投诉定级规则（服务端单一来源）。
     *
     * <p>返回的阈值与 GET /jobs 里 complaintLevel 的判定、以及列表排序所用规则完全同源
     * （都来自数据库的 complaint_rule_* 函数）。
     */
    @Operation(summary = "投诉定级规则", description = "定级规则（版本/等级序列/规模阈值/率与次数阈值），数字来自数据库函数")
    @GetMapping("/complaint-rules")
    public ComplaintRules complaintRules(AuthUser auth) {
        return complaintRepo.rules();
    }

    /** 证据说明校验（与 Rust 版 validate_evidence 完全一致：trim 后 20-5000 字符）。 */
    private static void validateEvidence(String evidence) {
        String trimmed = evidence == null ? "" : evidence.trim();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < 20 || length > 5000) {
            throw ApiException.badRequest("证据说明需在 20-5000 字符之间");
        }
    }
}
