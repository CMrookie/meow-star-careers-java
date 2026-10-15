package com.meowstar.careers.web;

import com.meowstar.careers.auth.AuthUser;
import com.meowstar.careers.error.ApiException;
import com.meowstar.careers.model.application.ApplicationPage;
import com.meowstar.careers.model.application.ApplicationQuery;
import com.meowstar.careers.model.application.ApplicationView;
import com.meowstar.careers.model.application.ApplyRequest;
import com.meowstar.careers.model.application.UpdateApplicationStatus;
import com.meowstar.careers.model.user.User;
import com.meowstar.careers.repo.ApplicationRepo;
import com.meowstar.careers.repo.UserRepo;
import com.meowstar.careers.ws.RealtimeNotifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 投递端点：投递（挂在 /jobs/{id}/apply）、列表（求职者=我的投递 / 招聘者=企业收件箱）、
 * 详情、状态流转。与 Rust 版 {@code handlers::applications} + {@code handlers::jobs::apply_job} 对齐。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "application", description = "在线投递与状态流转")
public class ApplicationsController {

    /** 投递状态全集（Rust APPLICATION_STATUSES）。 */
    private static final List<String> APPLICATION_STATUSES =
            List.of("pending", "viewed", "interviewing", "offered", "rejected", "withdrawn");
    /** 招聘者可推进到的状态（Rust RECRUITER_TRANSITIONS）。 */
    private static final List<String> RECRUITER_TRANSITIONS =
            List.of("viewed", "interviewing", "offered", "rejected");
    /** 求职者只能撤回（Rust SEEKER_WITHDRAW）。 */
    private static final String SEEKER_WITHDRAW = "withdrawn";

    private final ApplicationRepo applicationRepo;
    private final UserRepo userRepo;
    private final RealtimeNotifier notifier;

    public ApplicationsController(ApplicationRepo applicationRepo, UserRepo userRepo,
                                  RealtimeNotifier notifier) {
        this.applicationRepo = applicationRepo;
        this.userRepo = userRepo;
        this.notifier = notifier;
    }

    /** 状态取值校验（与 Rust ensure_status_valid 文案一致）。 */
    private static void ensureStatusValid(String status) {
        if (status == null || !APPLICATION_STATUSES.contains(status)) {
            throw ApiException.badRequest("status 取值: " + String.join(" / ", APPLICATION_STATUSES));
        }
    }

    /** 求职者投递职位（Rust {@code handlers::jobs::apply_job}，路径挂在 /jobs/{id}/apply）。 */
    @Operation(summary = "求职者投递职位", description = "一人一岗一次；职位须在招；resumeId 必须属于本人")
    @PostMapping("/jobs/{id}/apply")
    public ResponseEntity<ApplicationView> applyJob(AuthUser auth, @PathVariable UUID id,
                                                   @RequestBody ApplyRequest payload) {
        User seeker = userRepo.requireSeeker(auth.userId());
        UUID jobId = id;

        if (!applicationRepo.jobIsActive(jobId)) {
            throw ApiException.notFoundResource("job `" + jobId + "` 未在招或不存在");
        }
        if (applicationRepo.findByJobAndSeeker(jobId, seeker.id()).isPresent()) {
            throw ApiException.conflict("已投递过该职位");
        }

        String coverLetter = payload.coverLetter();
        if (coverLetter != null) {
            String trimmed = coverLetter.trim();
            if (trimmed.codePointCount(0, trimmed.length()) > 2000) {
                throw ApiException.badRequest("coverLetter 不能超过 2000 字符");
            }
        }

        ApplicationView application =
                applicationRepo.apply(jobId, seeker.id(), payload.resumeId(), coverLetter);
        return ResponseEntity.status(HttpStatus.CREATED).body(application);
    }

    /** 投递列表：求职者看自己的；招聘者看本企业的。 */
    @Operation(summary = "投递列表", description = "求职者看自己的投递；招聘者看本企业收到的投递")
    @GetMapping("/applications")
    public ApplicationPage listApplications(AuthUser auth, ApplicationQuery query) {
        User user = userRepo.get(auth.userId())
                .orElseThrow(() -> ApiException.notFoundUser(auth.userId()));

        if ("recruiter".equals(user.role())) {
            UUID companyId = user.companyId();
            if (companyId == null) {
                throw ApiException.forbidden("招聘者账号尚未绑定企业");
            }
            return applicationRepo.listForCompany(companyId, query);
        }
        return applicationRepo.listForSeeker(auth.userId(), query);
    }

    /** 投递详情（求职者本人或企业招聘者可看）。 */
    @Operation(summary = "投递详情", description = "求职者本人或该企业招聘者可看（越权 403）")
    @GetMapping("/applications/{id}")
    public ApplicationView getApplication(AuthUser auth, @PathVariable UUID id) {
        ApplicationView application = applicationRepo.getView(id)
                .orElseThrow(() -> ApiException.notFoundResource("application `" + id + "`"));

        User user = userRepo.get(auth.userId())
                .orElseThrow(() -> ApiException.notFoundUser(auth.userId()));

        boolean canView = application.seekerId().equals(auth.userId())
                || ("recruiter".equals(user.role())
                        && user.companyId() != null
                        && user.companyId().equals(application.companyId()));
        if (!canView) {
            throw ApiException.forbidden("无权查看该投递");
        }

        return application;
    }

    /** 更新投递状态：招聘者推进（viewed/interviewing/offered/rejected），求职者撤回（withdrawn）。 */
    @Operation(summary = "更新投递状态",
            description = "招聘者可设 viewed/interviewing/offered/rejected；求职者仅可撤回（withdrawn）")
    @PostMapping("/applications/{id}/status")
    public ApplicationView updateApplicationStatus(AuthUser auth, @PathVariable UUID id,
                                                   @RequestBody UpdateApplicationStatus payload) {
        String status = payload.status();
        ensureStatusValid(status);

        ApplicationView application = applicationRepo.getView(id)
                .orElseThrow(() -> ApiException.notFoundResource("application `" + id + "`"));
        User user = userRepo.get(auth.userId())
                .orElseThrow(() -> ApiException.notFoundUser(auth.userId()));

        if ("recruiter".equals(user.role())) {
            boolean companyOk = user.companyId() != null
                    && user.companyId().equals(application.companyId());
            if (!companyOk) {
                throw ApiException.forbidden("仅可操作本企业收到的投递");
            }
            if (!RECRUITER_TRANSITIONS.contains(status)) {
                throw ApiException.forbidden("招聘者可设置的状态: viewed / interviewing / offered / rejected");
            }
        } else {
            // 求职者只能撤回自己的投递
            if (!application.seekerId().equals(auth.userId())) {
                throw ApiException.forbidden("无权操作该投递");
            }
            if (!SEEKER_WITHDRAW.equals(status)) {
                throw ApiException.forbidden("求职者仅可撤回（withdrawn）");
            }
        }

        ApplicationView updated = applicationRepo.updateStatus(id, status)
                .orElseThrow(() -> ApiException.notFoundResource("application `" + id + "`"));
        // 实时推送：状态变化同步给求职者与本企业招聘者
        notifier.notifyApplication(updated, userRepo.recruitersOfCompany(updated.companyId()));
        return updated;
    }
}
