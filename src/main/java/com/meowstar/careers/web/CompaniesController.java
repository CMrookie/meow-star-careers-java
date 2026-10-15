package com.meowstar.careers.web;

import com.meowstar.careers.auth.AuthUser;
import com.meowstar.careers.error.ApiException;
import com.meowstar.careers.model.company.Company;
import com.meowstar.careers.model.user.User;
import com.meowstar.careers.repo.CompanyRepo;
import com.meowstar.careers.repo.UserRepo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企业端点：公开企业信息 / 招聘者我的企业。
 *
 * <p>两个端点都要求认证（对齐 Rust 版 {@code handlers::companies}）；企业不存在一律 404，
 * 文案为 {@code company `<id>` not found}。
 */
@RestController
@RequestMapping("/api/v1/companies")
@Tag(name = "company", description = "企业信息")
public class CompaniesController {

    private final CompanyRepo companyRepo;
    private final UserRepo userRepo;

    public CompaniesController(CompanyRepo companyRepo, UserRepo userRepo) {
        this.companyRepo = companyRepo;
        this.userRepo = userRepo;
    }

    /** 我的企业（仅招聘者）。 */
    @Operation(summary = "我的企业", description = "返回当前招聘者所属企业（仅招聘者）")
    @GetMapping("/mine")
    public Company myCompany(AuthUser auth) {
        User recruiter = userRepo.requireRecruiter(auth.userId());
        UUID companyId = recruiter.companyId();
        return companyRepo.get(companyId)
                .orElseThrow(() -> ApiException.notFoundResource("company `" + companyId + "`"));
    }

    /** 企业公开信息（需认证）。 */
    @Operation(summary = "企业公开信息", description = "按 id 返回企业信息；不存在返回 404")
    @GetMapping("/{id}")
    public Company getCompany(AuthUser auth, @PathVariable UUID id) {
        return companyRepo.get(id)
                .orElseThrow(() -> ApiException.notFoundResource("company `" + id + "`"));
    }
}
