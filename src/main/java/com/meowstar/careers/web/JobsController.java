package com.meowstar.careers.web;

import com.meowstar.careers.auth.AuthUser;
import com.meowstar.careers.error.ApiException;
import com.meowstar.careers.model.job.ActiveRequest;
import com.meowstar.careers.model.job.JobPage;
import com.meowstar.careers.model.job.JobQuery;
import com.meowstar.careers.model.job.JobView;
import com.meowstar.careers.model.job.NewJob;
import com.meowstar.careers.model.job.UpdateJob;
import com.meowstar.careers.model.pagination.PageQuery;
import com.meowstar.careers.model.pagination.Paginate;
import com.meowstar.careers.model.user.User;
import com.meowstar.careers.repo.JobRepo;
import com.meowstar.careers.repo.UserRepo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职位端点：公开浏览/搜索、招聘者发布管理、收藏（对齐 Rust 版 {@code handlers::jobs}）。
 *
 * <p>收藏列表 {@code GET /api/v1/saved-jobs} 挂在 {@code /api/v1} 下（与 Rust 版 app.rs 一致），
 * 因此类级映射为 {@code /api/v1}、方法级写具体路径。
 *
 * <p>列表排序由 {@link JobRepo} 的 SQL 完成（投诉等级从优到劣，翻页全局有序）；
 * 定级阈值只来自 SQL 函数，本控制器不做任何等级判定。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "job", description = "职位发布 / 搜索 / 收藏")
public class JobsController {

    /** 职位可用的工作性质（对齐 Rust 版 {@code models::job::JOB_TYPES}）。 */
    private static final List<String> JOB_TYPES =
            List.of("full_time", "part_time", "contract", "intern");

    private final JobRepo jobRepo;
    private final UserRepo userRepo;

    public JobsController(JobRepo jobRepo, UserRepo userRepo) {
        this.jobRepo = jobRepo;
        this.userRepo = userRepo;
    }

    /**
     * 公开搜索在招职位。
     *
     * <p>排序契约（定级 v2）：结果按用人单位投诉等级**从优到劣**返回（优秀 → 轻微 → 预警 →
     * 警告 → 严重）。等级按公司规模折算：企业申报了员工数且 &gt;=50 人时用每百人投诉率
     * （0.5/1.5/3.0%），未申报或 &lt;50 人退回投诉次数口径；同等级内「有规模折算的」优先，
     * 再按率（或次数）由小到大，然后发布时间倒序、id 兜底。排序在服务端完成，翻页全局有序。
     */
    @Operation(summary = "搜索在招职位",
            description = "搜索在招职位；按投诉等级从优到劣排序（优秀→轻微→预警→警告→严重），"
                    + "等级按公司规模折算（申报规模且 >=50 人时用每百人投诉率 0.5/1.5/3.0%，否则退回投诉次数）；"
                    + "同级内有规模折算者优先，再按率/次数小者优先，翻页全局有序。"
                    + "响应含 companyStaffSize（企业规模，未申报为 null）")
    @GetMapping("/jobs")
    public JobPage listJobs(AuthUser auth, JobQuery query) {
        validateSearchQuery(query);
        Paginate.Page page = Paginate.paginate(query.page(), query.pageSize());
        JobRepo.JobList result = jobRepo.searchActive(query);
        return new JobPage(result.items(), result.total(), page.page(), page.pageSize());
    }

    /** 发布职位（招聘者）。 */
    @Operation(summary = "发布职位", description = "招聘者发布职位（本企业），默认 jobType=full_time")
    @PostMapping("/jobs")
    public ResponseEntity<JobView> createJob(AuthUser auth, @RequestBody NewJob payload) {
        User recruiter = userRepo.requireRecruiter(auth.userId());
        validateNewJob(payload);

        UUID companyId = recruiter.companyId();
        JobView job = jobRepo.create(companyId, recruiter.id(), payload);
        return ResponseEntity.status(HttpStatus.CREATED).body(job);
    }

    /** 我的（本企业）职位。 */
    @Operation(summary = "我的职位",
            description = "本企业职位（含已下架）；排序与公开列表一致（投诉等级从优到劣、按公司规模折算），"
                    + "本企业内等级相同，故实际按发布时间倒序")
    @GetMapping("/jobs/my")
    public JobPage myJobs(AuthUser auth, PageQuery query) {
        User recruiter = userRepo.requireRecruiter(auth.userId());
        UUID companyId = recruiter.companyId();

        JobRepo.JobList result = jobRepo.listForCompany(companyId, query.page(), query.pageSize());
        Paginate.Page page = Paginate.paginate(query.page(), query.pageSize());
        return new JobPage(result.items(), result.total(), page.page(), page.pageSize());
    }

    /** 职位详情（在招任何人可见；已下架仅本企业招聘者可见，否则 404）。 */
    @Operation(summary = "职位详情", description = "公开上架职位任何人可见；已下架职位仅本企业招聘者可见，其余一律 404")
    @GetMapping("/jobs/{id}")
    public JobView getJob(AuthUser auth, @PathVariable UUID id) {
        Optional<JobView> active = jobRepo.getActive(id);
        if (active.isPresent()) {
            return active.get();
        }
        // 本企业招聘者允许查看已下架职位；非招聘者/未绑定企业按不存在处理（防探测）
        try {
            User recruiter = userRepo.requireRecruiter(auth.userId());
            Optional<JobView> job = jobRepo.getById(id);
            if (job.isPresent() && job.get().companyId().equals(recruiter.companyId())) {
                return job.get();
            }
        } catch (ApiException ignored) {
            // 角色/企业门禁不通过：与 Rust 版 if let Ok(recruiter) 一致，吞掉后统一 404
        }
        throw ApiException.notFoundResource("job `" + id + "`");
    }

    /** 更新职位（招聘者，仅本企业职位）。 */
    @Operation(summary = "更新职位", description = "招聘者更新本企业职位（仅更新提供的字段）")
    @PutMapping("/jobs/{id}")
    public JobView updateJob(AuthUser auth, @PathVariable UUID id, @RequestBody UpdateJob payload) {
        User recruiter = userRepo.requireRecruiter(auth.userId());
        validateUpdateJob(payload);

        JobView job = jobRepo.getById(id)
                .orElseThrow(() -> ApiException.notFoundResource("job `" + id + "`"));
        if (!job.companyId().equals(recruiter.companyId())) {
            throw ApiException.forbidden("仅可操作本企业职位");
        }

        return jobRepo.update(id, payload)
                .orElseThrow(() -> ApiException.notFoundResource("job `" + id + "`"));
    }

    /** 删除职位（招聘者，仅本企业职位）。 */
    @Operation(summary = "删除职位", description = "招聘者删除本企业职位（投递记录级联清理）")
    @DeleteMapping("/jobs/{id}")
    public ResponseEntity<Void> deleteJob(AuthUser auth, @PathVariable UUID id) {
        User recruiter = userRepo.requireRecruiter(auth.userId());

        JobView job = jobRepo.getById(id)
                .orElseThrow(() -> ApiException.notFoundResource("job `" + id + "`"));
        if (!job.companyId().equals(recruiter.companyId())) {
            throw ApiException.forbidden("仅可操作本企业职位");
        }

        jobRepo.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** 上架/下架职位（招聘者，仅本企业职位）。 */
    @Operation(summary = "上架/下架职位", description = "招聘者切换本企业职位的 isActive，返回更新后的职位")
    @PostMapping("/jobs/{id}/active")
    public JobView setJobActive(AuthUser auth, @PathVariable UUID id, @RequestBody ActiveRequest payload) {
        User recruiter = userRepo.requireRecruiter(auth.userId());

        JobView job = jobRepo.getById(id)
                .orElseThrow(() -> ApiException.notFoundResource("job `" + id + "`"));
        if (!job.companyId().equals(recruiter.companyId())) {
            throw ApiException.forbidden("仅可操作本企业职位");
        }

        jobRepo.setActive(id, payload.isActive());
        return jobRepo.getById(id)
                .orElseThrow(() -> ApiException.notFoundResource("job `" + id + "`"));
    }

    /** 收藏职位（任意角色可收藏，防重复；仅可收藏在招职位）。 */
    @Operation(summary = "收藏职位", description = "收藏在招职位（重复收藏幂等；职位不在招或不存在返回 404）")
    @PostMapping("/jobs/{id}/save")
    public ResponseEntity<Void> saveJob(AuthUser auth, @PathVariable UUID id) {
        if (jobRepo.getActive(id).isEmpty()) {
            throw ApiException.notFoundResource("job `" + id + "`");
        }
        jobRepo.save(auth.userId(), id);
        return ResponseEntity.noContent().build();
    }

    /** 取消收藏。 */
    @Operation(summary = "取消收藏", description = "取消收藏指定职位（幂等）")
    @PostMapping("/jobs/{id}/unsave")
    public ResponseEntity<Void> unsaveJob(AuthUser auth, @PathVariable UUID id) {
        jobRepo.unsave(auth.userId(), id);
        return ResponseEntity.noContent().build();
    }

    /** 我的收藏职位列表。 */
    @Operation(summary = "我的收藏职位",
            description = "收藏的职位；排序与公开列表一致（投诉等级从优到劣、按公司规模折算），"
                    + "同级内按最近收藏时间倒序")
    @GetMapping("/saved-jobs")
    public JobPage savedJobs(AuthUser auth, PageQuery query) {
        JobRepo.JobList result = jobRepo.listSaved(auth.userId(), query.page(), query.pageSize());
        Paginate.Page page = Paginate.paginate(query.page(), query.pageSize());
        return new JobPage(result.items(), result.total(), page.page(), page.pageSize());
    }

    // ---------------------------------------------------------------- 校验

    /** 搜索关键词等查询参数长度上限校验（与 Rust 版 validate_search_query 逐字一致）。 */
    private static void validateSearchQuery(JobQuery query) {
        if (query.keyword() != null
                && query.keyword().codePointCount(0, query.keyword().length()) > 200) {
            throw ApiException.badRequest("keyword 不能超过 200 字符");
        }
        if (query.location() != null
                && query.location().codePointCount(0, query.location().length()) > 100) {
            throw ApiException.badRequest("location 不能超过 100 字符");
        }
    }

    /** 发布职位参数校验（与 Rust 版 validate_new_job 逐字一致）。 */
    private static void validateNewJob(NewJob input) {
        String title = trimToEmpty(input.title());
        if (title.isEmpty()) {
            throw ApiException.badRequest("title 不能为空");
        }
        validateText("title", title, 128);

        String description = trimToEmpty(input.description());
        if (description.isEmpty()) {
            throw ApiException.badRequest("description 不能为空");
        }
        validateText("description", description, 10_000);

        validateText("requirements", input.requirements(), 10_000);
        validateText("location", input.location(), 100);
        validateText("experience", input.experience(), 100);
        validateText("education", input.education(), 100);
        validateJobType(input.jobType());
        validateSalary(input.salaryMin(), input.salaryMax());
    }

    /** 更新职位参数校验（与 Rust 版 validate_update_job 逐字一致）。 */
    private static void validateUpdateJob(UpdateJob input) {
        if (input.title() != null) {
            if (input.title().trim().isEmpty()) {
                throw ApiException.badRequest("title 不能为空");
            }
            validateText("title", input.title(), 128);
        }
        if (input.description() != null) {
            if (input.description().trim().isEmpty()) {
                throw ApiException.badRequest("description 不能为空");
            }
            validateText("description", input.description(), 10_000);
        }
        validateText("requirements", input.requirements(), 10_000);
        validateText("location", input.location(), 100);
        validateText("experience", input.experience(), 100);
        validateText("education", input.education(), 100);
        validateJobType(input.jobType());
        validateSalary(input.salaryMin(), input.salaryMax());
    }

    /** 文本长度（按 Unicode 码点计，等价 Rust 的 chars().count()；先 trim）。 */
    private static void validateText(String field, String value, int max) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.codePointCount(0, trimmed.length()) > max) {
            throw ApiException.badRequest(field + " 不能超过 " + max + " 字符");
        }
    }

    private static void validateJobType(String jobType) {
        if (jobType != null && !JOB_TYPES.contains(jobType)) {
            throw ApiException.badRequest("job_type 取值: " + String.join(" / ", JOB_TYPES));
        }
    }

    private static void validateSalary(Integer salaryMin, Integer salaryMax) {
        checkSalary("salary_min", salaryMin);
        checkSalary("salary_max", salaryMax);
        if (salaryMin != null && salaryMax != null && salaryMin > salaryMax) {
            throw ApiException.badRequest("salary_min 不能大于 salary_max");
        }
    }

    private static void checkSalary(String label, Integer value) {
        if (value != null && (value < 0 || value > 10_000_000)) {
            throw ApiException.badRequest(label + " 须在 0~10,000,000 之间");
        }
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
