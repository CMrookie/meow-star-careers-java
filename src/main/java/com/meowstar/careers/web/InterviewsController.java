package com.meowstar.careers.web;

import com.meowstar.careers.auth.AuthUser;
import com.meowstar.careers.error.ApiException;
import com.meowstar.careers.model.application.ApplicationView;
import com.meowstar.careers.model.interview.InterviewView;
import com.meowstar.careers.model.interview.NewInterview;
import com.meowstar.careers.model.user.User;
import com.meowstar.careers.repo.InterviewRepo;
import com.meowstar.careers.repo.UserRepo;
import com.meowstar.careers.ws.RealtimeNotifier;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 线上面试端点：发起（即时/预约）/ 我的列表 / 详情 / 开始 / 结束 / 取消
 * （对齐 Rust {@code handlers::interviews}）。每次状态变化都用
 * {@link RealtimeNotifier#notifyInterview(InterviewView)} 向双方推送 interview-updated。
 */
@RestController
@RequestMapping("/api/v1/interviews")
@Tag(name = "interview", description = "线上视频面试（房间/状态/信令经 WS 转发）")
public class InterviewsController {

    /** 非法/缺字段 JSON 的统一文案（对齐 Rust {@code error::json_error_handler}）。 */
    private static final String BAD_JSON = "malformed or invalid JSON request body";

    private final InterviewRepo interviewRepo;
    private final UserRepo userRepo;
    private final RealtimeNotifier notifier;

    public InterviewsController(InterviewRepo interviewRepo, UserRepo userRepo, RealtimeNotifier notifier) {
        this.interviewRepo = interviewRepo;
        this.userRepo = userRepo;
        this.notifier = notifier;
    }

    @Operation(summary = "发起面试", description = "招聘者对本企业收到的投递发起（即时或预约）；同一投递活跃面试唯一（409）")
    @PostMapping
    public ResponseEntity<InterviewView> createInterview(@RequestBody NewInterview payload, AuthUser auth) {
        if (payload == null || payload.applicationId() == null) {
            throw ApiException.badRequest(BAD_JSON);
        }

        ApplicationView application = recruiterOfApplication(auth.userId(), payload.applicationId());
        InterviewView view = interviewRepo.create(application, auth.userId(), payload.scheduledAt());
        notifier.notifyInterview(view);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @Operation(summary = "我的面试", description = "我作为招聘者或求职者参与的面试，最新优先")
    @GetMapping
    public List<InterviewView> listMine(AuthUser auth) {
        return interviewRepo.mine(auth.userId());
    }

    @Operation(summary = "面试详情", description = "仅参与者可见（非参与者 403，不存在 404）")
    @GetMapping("/{id}")
    public InterviewView getInterview(@PathVariable UUID id, AuthUser auth) {
        InterviewView view = interviewRepo.get(id)
                .orElseThrow(() -> ApiException.notFoundResource("interview `" + id + "`"));
        if (!view.interviewerId().equals(auth.userId()) && !view.intervieweeId().equals(auth.userId())) {
            throw ApiException.forbidden("非面试参与者");
        }
        return view;
    }

    @Operation(summary = "开始面试", description = "状态 invited -> in_progress（未到预约时间 400）")
    @PostMapping("/{id}/start")
    public InterviewView startInterview(@PathVariable UUID id, AuthUser auth) {
        return mutateAndNotify(id, interviewRepo.start(id, auth.userId()));
    }

    @Operation(summary = "结束面试", description = "状态 in_progress -> finished")
    @PostMapping("/{id}/finish")
    public InterviewView finishInterview(@PathVariable UUID id, AuthUser auth) {
        return mutateAndNotify(id, interviewRepo.finish(id, auth.userId()));
    }

    @Operation(summary = "取消面试", description = "状态 invited -> cancelled")
    @PostMapping("/{id}/cancel")
    public InterviewView cancelInterview(@PathVariable UUID id, AuthUser auth) {
        return mutateAndNotify(id, interviewRepo.cancel(id, auth.userId()));
    }

    /** 状态流转通用：仓储更新成功后向双方推送 interview-updated（对齐 Rust {@code mutate_and_notify}）。 */
    private InterviewView mutateAndNotify(UUID id, Optional<InterviewView> result) {
        InterviewView view = result
                .orElseThrow(() -> ApiException.notFoundResource("interview `" + id + "`"));
        notifier.notifyInterview(view);
        return view;
    }

    /** 发起面试前：必须是招聘者，且投递属于本企业（错误文案与 Rust {@code recruiter_of_application} 逐字一致）。 */
    private ApplicationView recruiterOfApplication(UUID userId, UUID applicationId) {
        User me = userRepo.get(userId).orElseThrow(() -> ApiException.notFoundUser(userId));
        if (!"recruiter".equals(me.role())) {
            throw ApiException.forbidden("仅招聘者可发起视频面试");
        }
        ApplicationView application = interviewRepo.findApplicationView(applicationId)
                .orElseThrow(() -> ApiException.notFoundResource("application `" + applicationId + "`"));
        boolean companyOk = me.companyId() != null && me.companyId().equals(application.companyId());
        if (!companyOk) {
            throw ApiException.forbidden("仅可对本企业收到的投递发起面试");
        }
        return application;
    }
}
