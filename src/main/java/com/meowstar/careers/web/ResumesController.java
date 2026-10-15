package com.meowstar.careers.web;

import com.meowstar.careers.auth.AuthUser;
import com.meowstar.careers.error.ApiException;
import com.meowstar.careers.model.resume.Resume;
import com.meowstar.careers.model.resume.ResumePage;
import com.meowstar.careers.model.resume.ResumeQuery;
import com.meowstar.careers.model.resume.ResumeWrite;
import com.meowstar.careers.repo.ResumeRepo;
import com.meowstar.careers.repo.UserRepo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * 简历端点（求职者管理自己；招聘者可检索公开简历）。
 * 隐私口径：非本人且非招聘者（或非公开）一律按 404 处理，不泄露简历是否存在。
 */
@RestController
@RequestMapping("/api/v1/resumes")
@Tag(name = "resume", description = "简历管理（求职者）/ 简历检索（招聘者）")
public class ResumesController {

    private final ResumeRepo resumeRepo;
    private final UserRepo userRepo;

    public ResumesController(ResumeRepo resumeRepo, UserRepo userRepo) {
        this.resumeRepo = resumeRepo;
        this.userRepo = userRepo;
    }

    /** 文本长度校验：按 trim 后的字符数（与 Rust validate_text 一致）。 */
    private static void validateText(String field, String value, int max) {
        String trimmed = value.trim();
        if (trimmed.codePointCount(0, trimmed.length()) > max) {
            throw ApiException.badRequest(field + " 不能超过 " + max + " 字符");
        }
    }

    /** 简历写入校验（与 Rust validate_resume 完全一致的口径与错误信息）。 */
    static void validateResume(ResumeWrite input) {
        String fullName = input.fullName() == null ? "" : input.fullName().trim();
        if (fullName.isEmpty()) {
            throw ApiException.badRequest("fullName 不能为空");
        }
        validateText("fullName", fullName, 64);

        String title = input.title() == null ? "" : input.title().trim();
        if (title.isEmpty()) {
            throw ApiException.badRequest("title（期望职位）不能为空");
        }
        validateText("title", title, 128);

        if (input.phone() != null) {
            validateText("phone", input.phone(), 32);
        }
        if (input.email() != null) {
            // Rust 此处用 v.trim().len()（UTF-8 字节数），故按字节对齐
            String email = input.email().trim();
            if (email.getBytes(StandardCharsets.UTF_8).length > 254) {
                throw ApiException.badRequest("email 不能超过 254 字符");
            }
        }
        if (input.education() != null) {
            validateText("education", input.education(), 128);
        }
        if (input.skills() != null) {
            validateText("skills", input.skills(), 5000);
        }
        if (input.summary() != null) {
            validateText("summary", input.summary(), 5000);
        }
        if (input.years() != null && (input.years() < 0 || input.years() > 100)) {
            throw ApiException.badRequest("years 须在 0-100 之间");
        }
    }

    /** 新建简历（求职者）。 */
    @Operation(summary = "新建简历", description = "仅求职者；fullName/title 必填")
    @PostMapping
    public ResponseEntity<Resume> createResume(AuthUser auth, @RequestBody ResumeWrite payload) {
        var seeker = userRepo.requireSeeker(auth.userId());
        validateResume(payload);

        Resume resume = resumeRepo.create(seeker.id(), payload);
        return ResponseEntity.status(HttpStatus.CREATED).body(resume);
    }

    /** 我的简历列表。 */
    @Operation(summary = "我的简历列表", description = "仅求职者；按创建时间倒序")
    @GetMapping
    public List<Resume> myResumes(AuthUser auth) {
        var seeker = userRepo.requireSeeker(auth.userId());
        return resumeRepo.listMine(seeker.id());
    }

    /** 招聘者检索公开简历。 */
    @Operation(summary = "检索公开简历", description = "仅招聘者；只返回 is_public=true 的简历")
    @GetMapping("/search")
    public ResumePage searchResumes(AuthUser auth, ResumeQuery query) {
        userRepo.requireRecruiter(auth.userId());
        if (query.keyword() != null) {
            String keyword = query.keyword();
            if (keyword.codePointCount(0, keyword.length()) > 200) {
                throw ApiException.badRequest("keyword 不能超过 200 字符");
            }
        }
        return resumeRepo.searchPublic(query);
    }

    /** 简历详情：本人可见全部；招聘者仅可见公开简历（隐私：非公开对他人按 404 处理）。 */
    @Operation(summary = "简历详情", description = "本人可见全部；招聘者仅可见公开简历（其余一律 404）")
    @GetMapping("/{id}")
    public Resume getResume(AuthUser auth, @PathVariable UUID id) {
        Resume resume = resumeRepo.get(id)
                .orElseThrow(() -> ApiException.notFoundResource("resume `" + id + "`"));

        boolean own = resume.userId().equals(auth.userId());
        boolean recruiterCanView = !own
                && resume.isPublic()
                && userRepo.get(auth.userId())
                        .map(u -> "recruiter".equals(u.role()))
                        .orElse(false);

        if (own || recruiterCanView) {
            return resume;
        }
        // 不泄露简历是否存在
        throw ApiException.notFoundResource("resume `" + id + "`");
    }

    /** 更新简历（仅本人）。 */
    @Operation(summary = "更新简历", description = "仅本人；只覆盖提供的字段")
    @PutMapping("/{id}")
    public Resume updateResume(AuthUser auth, @PathVariable UUID id, @RequestBody ResumeWrite payload) {
        var seeker = userRepo.requireSeeker(auth.userId());
        validateResume(payload);

        return resumeRepo.update(id, seeker.id(), payload)
                .orElseThrow(() -> ApiException.notFoundResource("resume `" + id + "`"));
    }

    /** 删除简历（仅本人）。 */
    @Operation(summary = "删除简历", description = "仅本人；不存在或非本人一律 404")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteResume(AuthUser auth, @PathVariable UUID id) {
        var seeker = userRepo.requireSeeker(auth.userId());

        if (!resumeRepo.delete(id, seeker.id())) {
            throw ApiException.notFoundResource("resume `" + id + "`");
        }
        return ResponseEntity.noContent().build();
    }
}
