package com.meowstar.careers.model.complaint;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 投诉定级规则（<b>服务端单一来源</b>）。
 *
 * <p>数字全部来自数据库里的 complaint_rule_* 函数 —— 排序、JobView 的
 * complaintLevel/complaintBasis/complaintRatePercent 与本接口三者同源，客户端只需渲染。
 */
@Schema(description = "投诉定级规则（服务端单一来源）")
public record ComplaintRules(
        @Schema(description = "规则版本（如 v2）") String version,
        @Schema(description = "等级序列（由优到劣）：excellent/minor/alert/warning/severe") List<String> levels,
        @Schema(description = "按率定级所需的最小企业规模（人），低于它退回次数口径") int minStaffSizeForRate,
        @Schema(description = "每百人投诉率上界（%）：0.5 / 1.5 / 3.0") List<Double> rateThresholds,
        @Schema(description = "投诉次数下界：[0, 1, 3, 6, 10]") List<Integer> countThresholds) {
}
