package com.meowstar.careers.model.application;

import java.util.UUID;

/** 投递职位请求体（resumeId 可不传）。 */
public record ApplyRequest(UUID resumeId, String coverLetter) {
}
