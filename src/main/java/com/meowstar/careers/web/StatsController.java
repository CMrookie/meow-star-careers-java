package com.meowstar.careers.web;

import com.meowstar.careers.auth.AuthUser;
import com.meowstar.careers.model.stats.CompanyQuality;
import com.meowstar.careers.model.stats.CompanyStatsQuery;
import com.meowstar.careers.model.stats.ReviewQueue;
import com.meowstar.careers.model.stats.ReviewStats;
import com.meowstar.careers.model.stats.ReviewerWorkload;
import com.meowstar.careers.model.stats.SeekerStats;
import com.meowstar.careers.model.user.User;
import com.meowstar.careers.repo.StatsRepo;
import com.meowstar.careers.repo.UserRepo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台统计端点：审核工作量与及时性 / 用人单位优劣 / 求职用户分析。
 *
 * <p>权限与可见范围（对齐 Rust {@code handlers::stats}）：
 * <ul>
 *   <li>{@code GET /stats/review}：审核账号与平台管理员。管理员看全部审核账号；
 *       审核账号<b>只看自己</b>（工作量看板不对同事互相暴露）。</li>
 *   <li>{@code GET /stats/companies}、{@code GET /stats/seekers}：<b>仅平台管理员</b>——它们覆盖全平台数据。</li>
 * </ul>
 *
 * <p>口径：审核只评价及时性（提交->审结、接单->审结与及时率），不返回任何通过/驳回结论指标。
 */
@RestController
@RequestMapping("/api/v1/stats")
@Tag(name = "stats", description = "平台统计：审核及时性 / 用人单位优劣 / 求职用户分析")
public class StatsController {

    private final StatsRepo statsRepo;
    private final UserRepo userRepo;

    public StatsController(StatsRepo statsRepo, UserRepo userRepo) {
        this.statsRepo = statsRepo;
        this.userRepo = userRepo;
    }

    /** 审核队列概览 + 审核账号工作量（管理员看全部，审核账号只看自己）。 */
    @Operation(summary = "审核统计",
            description = "审核统计：队列积压 + 每个审核账号的工作量与及时性。只衡量及时性，不含通过/驳回结论分布。")
    @GetMapping("/review")
    public ReviewStats reviewStats(AuthUser auth) {
        User me = userRepo.requireReviewer(auth.userId());
        ReviewQueue queue = statsRepo.reviewQueue(me.id(), StatsRepo.REVIEW_SLA_SECONDS);
        List<ReviewerWorkload> reviewers = "admin".equals(me.role())
                ? statsRepo.reviewerWorkloads(null, StatsRepo.REVIEW_SLA_SECONDS)
                : statsRepo.reviewerWorkloads(me.id(), StatsRepo.REVIEW_SLA_SECONDS);
        return new ReviewStats(queue, reviewers, StatsRepo.REVIEW_SLA_SECONDS);
    }

    /** 用人单位质量画像（仅平台管理员）。 */
    @Operation(summary = "用人单位质量列表",
            description = "用人单位优劣分析：投诉结构（待审/已核实/驳回）、职位规模、等级序、投诉率与综合质量分，最差在前")
    @GetMapping("/companies")
    public List<CompanyQuality> companyStats(AuthUser auth, CompanyStatsQuery query) {
        userRepo.requireAdmin(auth.userId());
        long limit = query.limitClamped();
        return statsRepo.companyQuality(limit);
    }

    /** 求职用户分析（仅平台管理员）。 */
    @Operation(summary = "求职用户统计",
            description = "求职用户分析：账号总量/活跃、注册趋势（近 12 个月）、简历与投递参与度、投递状态与分档分布")
    @GetMapping("/seekers")
    public SeekerStats seekerStats(AuthUser auth) {
        userRepo.requireAdmin(auth.userId());
        return statsRepo.seekerStats();
    }
}
